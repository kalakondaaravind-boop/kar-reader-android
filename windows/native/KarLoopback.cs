// KAR READER system-audio helper.
// Records what the computer is playing (the other people on a call) using WASAPI loopback and
// writes a 16 kHz mono 16-bit WAV. Stops when it reads "stop" on stdin, or when stdin closes.
// Written for the C# 5 compiler that ships with Windows (.NET Framework 4), so no newer syntax.
using System;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading;
using System.Diagnostics;

[ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")]
class MMDeviceEnumeratorComObject { }

[ComImport, Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IMMDeviceEnumerator
{
    [PreserveSig] int EnumAudioEndpoints(int dataFlow, int stateMask, out IntPtr devices);
    [PreserveSig] int GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice device);
    [PreserveSig] int GetDevice([MarshalAs(UnmanagedType.LPWStr)] string id, out IMMDevice device);
    [PreserveSig] int RegisterEndpointNotificationCallback(IntPtr client);
    [PreserveSig] int UnregisterEndpointNotificationCallback(IntPtr client);
}

[ComImport, Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IMMDevice
{
    [PreserveSig] int Activate(ref Guid iid, int clsCtx, IntPtr activationParams, [MarshalAs(UnmanagedType.IUnknown)] out object iface);
    [PreserveSig] int OpenPropertyStore(int access, out IntPtr properties);
    [PreserveSig] int GetId([MarshalAs(UnmanagedType.LPWStr)] out string id);
    [PreserveSig] int GetState(out int state);
}

[ComImport, Guid("1CB9AD4C-DBFA-4c32-B178-C2F568A703B2"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IAudioClient
{
    [PreserveSig] int Initialize(int shareMode, int streamFlags, long bufferDuration, long periodicity, IntPtr format, IntPtr sessionGuid);
    [PreserveSig] int GetBufferSize(out uint frames);
    [PreserveSig] int GetStreamLatency(out long latency);
    [PreserveSig] int GetCurrentPadding(out uint frames);
    [PreserveSig] int IsFormatSupported(int shareMode, IntPtr format, out IntPtr closest);
    [PreserveSig] int GetMixFormat(out IntPtr format);
    [PreserveSig] int GetDevicePeriod(out long defaultPeriod, out long minimumPeriod);
    [PreserveSig] int Start();
    [PreserveSig] int Stop();
    [PreserveSig] int Reset();
    [PreserveSig] int SetEventHandle(IntPtr handle);
    [PreserveSig] int GetService(ref Guid iid, [MarshalAs(UnmanagedType.IUnknown)] out object service);
}

[ComImport, Guid("C8ADBD64-E71E-48a0-A4DE-185C395CD317"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IAudioCaptureClient
{
    [PreserveSig] int GetBuffer(out IntPtr data, out uint frames, out uint flags, out ulong devicePosition, out ulong qpcPosition);
    [PreserveSig] int ReleaseBuffer(uint frames);
    [PreserveSig] int GetNextPacketSize(out uint frames);
}

static class Program
{
    const int TargetRate = 16000;
    static volatile bool stopRequested;

    static int Main(string[] args)
    {
        if (args.Length < 1) { Console.WriteLine("ERROR: usage: kar-loopback <output.wav>"); return 2; }
        string outPath = args[0];
        try
        {
            Thread watcher = new Thread(delegate ()
            {
                try { string line; while ((line = Console.In.ReadLine()) != null) { if (line.Trim().ToLowerInvariant() == "stop") break; } } catch { }
                stopRequested = true;
            });
            watcher.IsBackground = true;
            watcher.Start();
            Record(outPath);
            return 0;
        }
        catch (Exception e)
        {
            Console.WriteLine("ERROR: " + e.Message);
            return 1;
        }
    }

    static void Record(string outPath)
    {
        IMMDeviceEnumerator enumerator = (IMMDeviceEnumerator)new MMDeviceEnumeratorComObject();
        IMMDevice device;
        Check(enumerator.GetDefaultAudioEndpoint(0 /* render */, 0 /* console */, out device), "no playback device");

        Guid iidClient = new Guid("1CB9AD4C-DBFA-4c32-B178-C2F568A703B2");
        object clientObj;
        Check(device.Activate(ref iidClient, 23 /* CLSCTX_ALL */, IntPtr.Zero, out clientObj), "can't open the playback device");
        IAudioClient client = (IAudioClient)clientObj;

        IntPtr fmt;
        Check(client.GetMixFormat(out fmt), "can't read the audio format");
        int tag = Marshal.ReadInt16(fmt, 0) & 0xFFFF;
        int channels = Marshal.ReadInt16(fmt, 2);
        int rate = Marshal.ReadInt32(fmt, 4);
        int blockAlign = Marshal.ReadInt16(fmt, 12);
        int bits = Marshal.ReadInt16(fmt, 14);
        bool isFloat = tag == 3;
        if (tag == 0xFFFE) isFloat = Marshal.ReadInt32(fmt, 24) == 3;
        if (channels < 1 || rate < 8000 || blockAlign < 1) throw new Exception("unsupported audio format");

        // AUDCLNT_STREAMFLAGS_LOOPBACK = 0x00020000, shared mode, one second buffer.
        Check(client.Initialize(0, 0x00020000, 10000000L, 0, fmt, IntPtr.Zero), "can't capture the playback audio");
        Guid iidCapture = new Guid("C8ADBD64-E71E-48a0-A4DE-185C395CD317");
        object capObj;
        Check(client.GetService(ref iidCapture, out capObj), "can't read the playback audio");
        IAudioCaptureClient capture = (IAudioCaptureClient)capObj;

        using (FileStream fs = new FileStream(outPath, FileMode.Create, FileAccess.ReadWrite, FileShare.Read))
        {
            WriteHeader(fs, 0);
            long dataBytes = 0;
            Check(client.Start(), "can't start capturing");
            Console.WriteLine("READY");
            Console.Out.Flush();

            Stopwatch clock = Stopwatch.StartNew();
            double step = (double)rate / TargetRate;   // source frames per output sample
            double pos = 0;                            // fractional read position inside the current packet
            float last = 0;                            // last mono sample of the previous packet, for interpolation
            long written = 0;                          // output samples written
            long lastSync = 0;

            while (!stopRequested)
            {
                uint packet;
                Check(capture.GetNextPacketSize(out packet), "capture failed");
                if (packet == 0)
                {
                    // Nothing is playing. Keep the timeline honest by writing silence for the elapsed time.
                    long should = clock.ElapsedMilliseconds * TargetRate / 1000;
                    if (should > written + 800)
                    {
                        long pad = should - written;
                        dataBytes += WriteSilence(fs, pad);
                        written += pad;
                    }
                    Thread.Sleep(10);
                    continue;
                }
                while (packet > 0 && !stopRequested)
                {
                    IntPtr data; uint frames; uint flags; ulong dp; ulong qp;
                    Check(capture.GetBuffer(out data, out frames, out flags, out dp, out qp), "capture failed");
                    bool silent = (flags & 2) != 0;
                    float[] mono = new float[frames];
                    if (!silent)
                    {
                        for (int i = 0; i < frames; i++)
                        {
                            double sum = 0;
                            for (int c = 0; c < channels; c++)
                            {
                                int off = i * blockAlign + c * (bits / 8);
                                if (isFloat && bits == 32) sum += BitConverter.ToSingle(ReadBytes(data, off, 4), 0);
                                else if (bits == 16) sum += Marshal.ReadInt16(data, off) / 32768.0;
                                else if (bits == 32) sum += Marshal.ReadInt32(data, off) / 2147483648.0;
                                else if (bits == 24) sum += (((Marshal.ReadByte(data, off + 2) << 24) | (Marshal.ReadByte(data, off + 1) << 16) | (Marshal.ReadByte(data, off) << 8)) >> 8) / 8388608.0;
                            }
                            mono[i] = (float)(sum / channels);
                        }
                    }
                    capture.ReleaseBuffer(frames);

                    // Linear-interpolating resample to 16 kHz.
                    byte[] outBuf = new byte[(int)(frames / step) * 2 + 8];
                    int n = 0;
                    while (pos < frames)
                    {
                        int i0 = (int)Math.Floor(pos);
                        double frac = pos - i0;
                        float a = i0 == 0 ? last : mono[i0 - 1];
                        if (i0 >= frames) break;
                        float b = mono[i0];
                        // interpolate between sample i0-1 and i0 (position pos maps to i0-1+frac)
                        float s = (float)(a + (b - a) * frac);
                        if (s > 1f) s = 1f; else if (s < -1f) s = -1f;
                        short v = (short)(s * 32767f);
                        if (n + 2 > outBuf.Length) break;
                        outBuf[n++] = (byte)(v & 0xFF);
                        outBuf[n++] = (byte)((v >> 8) & 0xFF);
                        pos += step;
                    }
                    pos -= frames;
                    if (frames > 0) last = mono[frames - 1];
                    fs.Write(outBuf, 0, n);
                    dataBytes += n;
                    written += n / 2;

                    Check(capture.GetNextPacketSize(out packet), "capture failed");
                }
                if (dataBytes - lastSync > TargetRate * 2L * 10) { WriteHeader(fs, dataBytes); lastSync = dataBytes; }
            }
            client.Stop();
            WriteHeader(fs, dataBytes);
        }
    }

    static byte[] ReadBytes(IntPtr p, int off, int count)
    {
        byte[] b = new byte[count];
        Marshal.Copy(new IntPtr(p.ToInt64() + off), b, 0, count);
        return b;
    }

    static long WriteSilence(FileStream fs, long samples)
    {
        byte[] zeros = new byte[8192];
        long bytes = samples * 2;
        long left = bytes;
        while (left > 0) { int n = (int)Math.Min(left, zeros.Length); fs.Write(zeros, 0, n); left -= n; }
        return bytes;
    }

    // Writes (or rewrites) the 44-byte WAV header, then returns to the end of the file.
    static void WriteHeader(FileStream fs, long dataBytes)
    {
        long end = fs.Position;
        fs.Seek(0, SeekOrigin.Begin);
        BinaryWriter w = new BinaryWriter(fs);
        w.Write(new char[] { 'R', 'I', 'F', 'F' });
        w.Write((int)(36 + dataBytes));
        w.Write(new char[] { 'W', 'A', 'V', 'E', 'f', 'm', 't', ' ' });
        w.Write(16);
        w.Write((short)1);
        w.Write((short)1);
        w.Write(TargetRate);
        w.Write(TargetRate * 2);
        w.Write((short)2);
        w.Write((short)16);
        w.Write(new char[] { 'd', 'a', 't', 'a' });
        w.Write((int)dataBytes);
        w.Flush();
        fs.Seek(Math.Max(end, 44), SeekOrigin.Begin);
    }

    static void Check(int hr, string what)
    {
        if (hr < 0) throw new Exception(what + " (0x" + hr.ToString("X8") + ")");
    }
}
