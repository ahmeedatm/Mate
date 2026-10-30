using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Linq;
using System.Runtime.InteropServices;
using System.Text.RegularExpressions;
using System.Threading;

namespace UnoMate
{
    public static partial class Native
    {
        // --- Performances de la carte graphique (widget Performances de la tablette). Cartes, nom et mémoire dédiée
        // par DXGI ; utilisation par les compteurs PDH « GPU Engine », comme le Gestionnaire des tâches ; température
        // et ventilateur par D3DKMTQueryAdapterInfo. Valeurs brutes : le filtrage (0 illisible, aberrations,
        // ventilateur à l'arrêt) est fait par src/perf.ts.

        [ComImport, Guid("770AAE78-F26F-4DBA-A829-253C83D1B387"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        interface IDXGIFactory1
        {
            // IDXGIObject puis IDXGIFactory : emplacements de la table seulement, jamais appelés.
            void SetPrivateData();
            void SetPrivateDataInterface();
            void GetPrivateData();
            void GetParent();
            void EnumAdapters();
            void MakeWindowAssociation();
            void GetWindowAssociation();
            void CreateSwapChain();
            void CreateSoftwareAdapter();
            [PreserveSig] int EnumAdapters1(uint index, out IDXGIAdapter1 adapter);
        }

        [ComImport, Guid("29038F61-3839-4626-91FD-086879011A05"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        interface IDXGIAdapter1
        {
            // IDXGIObject puis IDXGIAdapter : emplacements de la table seulement, jamais appelés.
            void SetPrivateData();
            void SetPrivateDataInterface();
            void GetPrivateData();
            void GetParent();
            void EnumOutputs();
            void GetDesc();
            void CheckInterfaceSupport();
            [PreserveSig] int GetDesc1(out DXGI_ADAPTER_DESC1 desc);
        }

        [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
        struct DXGI_ADAPTER_DESC1
        {
            [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)] public string Description;
            public uint VendorId, DeviceId, SubSysId, Revision;
            public UIntPtr DedicatedVideoMemory, DedicatedSystemMemory, SharedSystemMemory;
            public uint LuidLowPart;
            public int LuidHighPart;
            public uint Flags;
        }

        [DllImport("dxgi.dll")]
        static extern int CreateDXGIFactory1(ref Guid riid, [MarshalAs(UnmanagedType.Interface)] out IDXGIFactory1 factory);

        // d3dkmthk.h (Windows SDK 10.0.26100, shared/) : D3DKMT_HANDLE = UINT, NTSTATUS en retour.

        [StructLayout(LayoutKind.Sequential)]
        struct D3DKMT_OPENADAPTERFROMLUID
        {
            public uint LuidLowPart;
            public int LuidHighPart;
            public uint hAdapter;
        }

        [StructLayout(LayoutKind.Sequential)]
        struct D3DKMT_QUERYADAPTERINFO
        {
            public uint hAdapter;
            public int Type;
            public IntPtr pPrivateDriverData;
            public uint PrivateDriverDataSize;
        }

        [StructLayout(LayoutKind.Sequential)]
        struct D3DKMT_CLOSEADAPTER
        {
            public uint hAdapter;
        }

        /** Alignement naturel des ULONGLONG : 4 octets de bourrage après PhysicalAdapterIndex, 64 octets en tout. */
        [StructLayout(LayoutKind.Explicit, Size = 64)]
        struct D3DKMT_ADAPTER_PERFDATA
        {
            [FieldOffset(0)] public uint PhysicalAdapterIndex;
            [FieldOffset(8)] public ulong MemoryFrequency;
            [FieldOffset(16)] public ulong MaxMemoryFrequency;
            [FieldOffset(24)] public ulong MaxMemoryFrequencyOC;
            [FieldOffset(32)] public ulong MemoryBandwidth;
            [FieldOffset(40)] public ulong PCIEBandwidth;
            [FieldOffset(48)] public uint FanRPM;
            [FieldOffset(52)] public uint Power;
            /** Dixièmes de degré Celsius. */
            [FieldOffset(56)] public uint Temperature;
            [FieldOffset(60)] public byte PowerStateOverride;
        }

        const int KMTQAITYPE_ADAPTERPERFDATA = 62;

        [DllImport("gdi32.dll")] static extern int D3DKMTOpenAdapterFromLuid(ref D3DKMT_OPENADAPTERFROMLUID open);
        [DllImport("gdi32.dll")] static extern int D3DKMTQueryAdapterInfo(ref D3DKMT_QUERYADAPTERINFO query);
        [DllImport("gdi32.dll")] static extern int D3DKMTCloseAdapter(ref D3DKMT_CLOSEADAPTER close);

        [DllImport("pdh.dll", EntryPoint = "PdhOpenQueryW", CharSet = CharSet.Unicode)]
        static extern int PdhOpenQuery(string dataSource, IntPtr userData, out IntPtr query);
        [DllImport("pdh.dll", EntryPoint = "PdhAddEnglishCounterW", CharSet = CharSet.Unicode)]
        static extern int PdhAddEnglishCounter(IntPtr query, string path, IntPtr userData, out IntPtr counter);
        [DllImport("pdh.dll")] static extern int PdhCollectQueryData(IntPtr query);
        [DllImport("pdh.dll", EntryPoint = "PdhGetFormattedCounterArrayW", CharSet = CharSet.Unicode)]
        static extern int PdhGetFormattedCounterArray(IntPtr counter, uint format, ref uint bufferSize, out uint itemCount, IntPtr items);
        [DllImport("pdh.dll")] static extern int PdhGetFormattedCounterValue(IntPtr counter, uint format, IntPtr type, out PDH_FMT_COUNTERVALUE value);
        [DllImport("pdh.dll")] static extern int PdhCloseQuery(IntPtr query);

        /** PDH_FMT_COUNTERVALUE : CStatus puis l'union des valeurs, alignée sur 8 (le double), en 32 comme en 64 bits. */
        [StructLayout(LayoutKind.Explicit, Size = 16)]
        struct PDH_FMT_COUNTERVALUE
        {
            [FieldOffset(0)] public uint CStatus;
            [FieldOffset(8)] public double DoubleValue;
        }

        const uint PDH_FMT_DOUBLE = 0x200;
        const int PDH_MORE_DATA = unchecked((int)0x800007D2);
        /** PDH_FMT_COUNTERVALUE_ITEM_W : szName (pointeur) puis FmtValue { CStatus, double aligné sur 8 }, en 32 comme en 64 bits. */
        const int PdhItemSize = 24;
        const uint DXGI_ADAPTER_FLAG_SOFTWARE = 2;

        class GpuAdapter
        {
            public string Luid, Name;
            public uint LuidLowPart;
            public int LuidHighPart;
            public long DedicatedMb;
            public bool Software;
        }

        /** Utilisation d'une carte, sommes des instances PDH (processus). */
        class CardEngines
        {
            /** Par moteur physique ("phys/eng") : la règle du Gestionnaire des tâches. */
            public readonly Dictionary<string, double> ByEngine = new Dictionary<string, double>();
            /** Par type de moteur (3D, Copy, VideoDecode…), pour le diagnostic. */
            public readonly Dictionary<string, double> ByType = new Dictionary<string, double>();
        }

        /**
         * Requête PDH gardée ouverte entre deux appels : l'utilisation est un taux, mesuré entre deux relevés. Deux
         * relevés trop rapprochés donneraient un taux faux (un diagnostic juste après une lecture du widget) : en
         * dessous de MinSampleMs, la dernière mesure est resservie. Au-delà de StaleMs, le taux serait la moyenne de
         * toute la pause du widget : comme au tout premier appel, le relevé ne sert que de point de départ et un
         * second relevé, BaselineMs plus tard, donne le taux.
         */
        static IntPtr gpuQuery, gpuCounter;
        static readonly Stopwatch gpuClock = Stopwatch.StartNew();
        static long gpuSampledAt = -1;
        static Dictionary<string, CardEngines> gpuLastUsage;
        static string gpuLastError;
        const int MinSampleMs = 500;
        const int StaleMs = 5000;
        /** Écart entre le point de départ et le relevé qui suit, comme la première mesure du processeur (src/perf.ts). */
        const int BaselineMs = 250;

        /** Échec d'un appel avec son code (NTSTATUS, PDH_STATUS), pour le diagnostic. */
        static string CallError(string call, int status)
        {
            return call + " : 0x" + status.ToString("X8");
        }

        /** LUID au format des instances PDH ("luid_0x00000000_0x0000D1B3") : partie haute puis partie basse. */
        static string LuidKey(uint high, uint low)
        {
            return "0x" + high.ToString("X8") + "_0x" + low.ToString("X8");
        }

        static List<GpuAdapter> GpuAdapters()
        {
            var list = new List<GpuAdapter>();
            var iid = typeof(IDXGIFactory1).GUID;
            IDXGIFactory1 factory;
            Marshal.ThrowExceptionForHR(CreateDXGIFactory1(ref iid, out factory));
            try
            {
                for (uint i = 0; ; i++)
                {
                    IDXGIAdapter1 adapter;
                    if (factory.EnumAdapters1(i, out adapter) != 0 || adapter == null) break; // DXGI_ERROR_NOT_FOUND : fin de la liste
                    try
                    {
                        DXGI_ADAPTER_DESC1 desc;
                        if (adapter.GetDesc1(out desc) != 0) continue;
                        list.Add(new GpuAdapter
                        {
                            Luid = LuidKey((uint)desc.LuidHighPart, desc.LuidLowPart),
                            Name = (desc.Description ?? "").Trim(),
                            LuidLowPart = desc.LuidLowPart,
                            LuidHighPart = desc.LuidHighPart,
                            DedicatedMb = (long)(desc.DedicatedVideoMemory.ToUInt64() / (1024 * 1024)),
                            Software = (desc.Flags & DXGI_ADAPTER_FLAG_SOFTWARE) != 0,
                        });
                    }
                    finally { Marshal.ReleaseComObject(adapter); }
                }
            }
            finally { Marshal.ReleaseComObject(factory); }
            return list;
        }

        /** Utilisation des moteurs par carte (LUID) ; null en cas d'échec (`error`). */
        static Dictionary<string, CardEngines> EngineUsage(out string error)
        {
            if (gpuSampledAt < 0 || gpuClock.ElapsedMilliseconds - gpuSampledAt >= MinSampleMs) gpuLastUsage = SampleEngines(out gpuLastError);
            error = gpuLastError;
            return gpuLastUsage;
        }

        static Dictionary<string, CardEngines> SampleEngines(out string error)
        {
            error = null;
            if (gpuQuery == IntPtr.Zero)
            {
                IntPtr query, counter;
                int s = PdhOpenQuery(null, IntPtr.Zero, out query);
                if (s != 0) { error = CallError("PdhOpenQuery", s); return null; }
                s = PdhAddEnglishCounter(query, @"\GPU Engine(*)\Utilization Percentage", IntPtr.Zero, out counter);
                if (s != 0) { PdhCloseQuery(query); error = CallError("PdhAddEnglishCounter", s); return null; }
                gpuQuery = query;
                gpuCounter = counter;
            }
            var now = gpuClock.ElapsedMilliseconds;
            int status = PdhCollectQueryData(gpuQuery);
            if (status != 0) { error = CallError("PdhCollectQueryData", status); return null; }
            var baseline = gpuSampledAt < 0 || now - gpuSampledAt > StaleMs;
            gpuSampledAt = now;
            if (baseline)
            {
                // Simple point de départ : le taux se mesure sur le relevé suivant, pris peu après.
                Thread.Sleep(BaselineMs);
                now = gpuClock.ElapsedMilliseconds;
                status = PdhCollectQueryData(gpuQuery);
                if (status != 0) { error = CallError("PdhCollectQueryData", status); return null; }
                gpuSampledAt = now;
            }

            uint size = 0, count;
            var usage = new Dictionary<string, CardEngines>(StringComparer.OrdinalIgnoreCase);
            status = PdhGetFormattedCounterArray(gpuCounter, PDH_FMT_DOUBLE, ref size, out count, IntPtr.Zero);
            if (status == 0) return usage; // aucune instance : aucun moteur en service
            if (status != PDH_MORE_DATA) { error = CallError("PdhGetFormattedCounterArray", status); return null; }
            var buffer = Marshal.AllocHGlobal((int)size);
            try
            {
                status = PdhGetFormattedCounterArray(gpuCounter, PDH_FMT_DOUBLE, ref size, out count, buffer);
                if (status != 0) { error = CallError("PdhGetFormattedCounterArray", status); return null; }
                var instance = new Regex("luid_0x([0-9a-f]{1,8})_0x([0-9a-f]{1,8})_phys_(\\d+)_eng_(\\d+)_engtype_(.*)$", RegexOptions.IgnoreCase);
                for (int i = 0; i < count; i++)
                {
                    var item = buffer + i * PdhItemSize;
                    int itemStatus = Marshal.ReadInt32(item, 8);
                    if (itemStatus != 0 && itemStatus != 1) continue; // PDH_CSTATUS_VALID_DATA, PDH_CSTATUS_NEW_DATA
                    var m = instance.Match(Marshal.PtrToStringUni(Marshal.ReadIntPtr(item)) ?? "");
                    if (!m.Success) continue;
                    var value = BitConverter.Int64BitsToDouble(Marshal.ReadInt64(item, 16));
                    var luid = LuidKey(Convert.ToUInt32(m.Groups[1].Value, 16), Convert.ToUInt32(m.Groups[2].Value, 16));
                    CardEngines card;
                    if (!usage.TryGetValue(luid, out card)) usage[luid] = card = new CardEngines();
                    Add(card.ByEngine, m.Groups[3].Value + "/" + m.Groups[4].Value, value);
                    Add(card.ByType, m.Groups[5].Value, value);
                }
                return usage;
            }
            finally { Marshal.FreeHGlobal(buffer); }
        }

        /** Température (dixièmes de °C) et ventilateur (tr/min) bruts ; en cas d'échec, null et l'erreur. */
        static string AdapterPerfData(GpuAdapter adapter, out uint? temperature, out uint? fanRpm)
        {
            temperature = null;
            fanRpm = null;
            try
            {
                var open = new D3DKMT_OPENADAPTERFROMLUID { LuidLowPart = adapter.LuidLowPart, LuidHighPart = adapter.LuidHighPart };
                int status = D3DKMTOpenAdapterFromLuid(ref open);
                if (status != 0) return CallError("D3DKMTOpenAdapterFromLuid", status);
                var size = Marshal.SizeOf(typeof(D3DKMT_ADAPTER_PERFDATA));
                var buffer = IntPtr.Zero;
                try
                {
                    buffer = Marshal.AllocHGlobal(size);
                    Marshal.StructureToPtr(new D3DKMT_ADAPTER_PERFDATA(), buffer, false); // PhysicalAdapterIndex 0
                    var query = new D3DKMT_QUERYADAPTERINFO
                    {
                        hAdapter = open.hAdapter, Type = KMTQAITYPE_ADAPTERPERFDATA, pPrivateDriverData = buffer, PrivateDriverDataSize = (uint)size,
                    };
                    status = D3DKMTQueryAdapterInfo(ref query);
                    if (status != 0) return CallError("D3DKMTQueryAdapterInfo", status);
                    var data = (D3DKMT_ADAPTER_PERFDATA)Marshal.PtrToStructure(buffer, typeof(D3DKMT_ADAPTER_PERFDATA));
                    temperature = data.Temperature;
                    fanRpm = data.FanRPM;
                    return null;
                }
                finally
                {
                    if (buffer != IntPtr.Zero) Marshal.FreeHGlobal(buffer);
                    var close = new D3DKMT_CLOSEADAPTER { hAdapter = open.hAdapter };
                    D3DKMTCloseAdapter(ref close);
                }
            }
            catch (Exception e) { return e.Message; } // fonction absente d'un Windows ancien
        }

        static void Add(Dictionary<string, double> sums, string key, double value)
        {
            double sum;
            sums.TryGetValue(key, out sum);
            sums[key] = sum + value;
        }

        /** Utilisation d'une carte selon le Gestionnaire des tâches : le moteur physique le plus occupé. */
        static double CardUsage(Dictionary<string, CardEngines> usage, GpuAdapter adapter)
        {
            CardEngines card;
            return usage.TryGetValue(adapter.Luid, out card) && card.ByEngine.Count > 0 ? card.ByEngine.Values.Max() : 0;
        }

        static bool IsDedicated(GpuAdapter adapter)
        {
            return !adapter.Software && adapter.DedicatedMb >= 512;
        }

        /**
         * Sans `detail` : la carte retenue (la dédiée s'il y en a une, sinon la première), null si aucune n'est
         * lisible. Avec `detail` : toutes les cartes et leurs valeurs brutes, pour le diagnostic d'un PC à carte
         * dédiée.
         */
        static object Perf(Dictionary<string, object> req)
        {
            var detail = Program.Bool(req, "detail");
            List<GpuAdapter> adapters;
            string dxgiError = null;
            try { adapters = GpuAdapters(); }
            catch (Exception e) { adapters = new List<GpuAdapter>(); dxgiError = e.Message; }
            string pdhError;
            var usage = EngineUsage(out pdhError);

            if (detail)
            {
                var list = new List<object>();
                foreach (var a in adapters)
                {
                    uint? temperature, fanRpm;
                    var perfDataError = AdapterPerfData(a, out temperature, out fanRpm);
                    CardEngines engines = null;
                    if (usage != null) usage.TryGetValue(a.Luid, out engines);
                    list.Add(new Dictionary<string, object>
                    {
                        { "luid", a.Luid }, { "name", a.Name }, { "dedicatedMemoryMb", a.DedicatedMb }, { "software", a.Software },
                        { "usage", usage != null ? (object)CardUsage(usage, a) : null }, { "engines", engines != null ? engines.ByType : new Dictionary<string, double>() },
                        { "tempRaw", temperature }, { "fanRaw", fanRpm }, { "perfDataError", perfDataError },
                    });
                }
                return new Dictionary<string, object> { { "adapters", list }, { "dxgiError", dxgiError }, { "pdhError", pdhError } };
            }

            var cards = adapters.Where(a => !a.Software).ToList();
            // Les puces AMD intégrées déclarent aussi 512 Mo à 2 Go : la dédiée est celle qui a le plus de mémoire.
            var card = cards.Where(IsDedicated).OrderByDescending(a => a.DedicatedMb).FirstOrDefault() ?? cards.FirstOrDefault();
            if (card == null || usage == null) return null;
            var res = new Dictionary<string, object> { { "name", card.Name }, { "usage", CardUsage(usage, card) }, { "dedicated", IsDedicated(card) } };
            uint? temp, fan;
            if (AdapterPerfData(card, out temp, out fan) == null)
            {
                res["tempC"] = (int)Math.Round(temp.Value / 10.0, MidpointRounding.AwayFromZero);
                res["fanRpm"] = fan.Value;
            }
            return res;
        }

        // --- Activité du processeur comme le Gestionnaire des tâches : « % Processor Utility » (rendement du
        // processeur), rapportée à la fréquence nominale, et non « % Processor Time » (os.cpus() de Node), qui sous-estime
        // la charge quand le processeur accélère. Même requête gardée ouverte et mêmes délais que l'utilisation du GPU.

        static IntPtr cpuQuery, cpuCounter;
        static readonly Stopwatch cpuClock = Stopwatch.StartNew();
        static long cpuSampledAt = -1;
        static double? cpuLastValue;

        /** Pourcentage entier de 0 à 100 ; null si le compteur est illisible (Windows ancien, compteurs désactivés). */
        static object Cpu()
        {
            if (cpuSampledAt < 0 || cpuClock.ElapsedMilliseconds - cpuSampledAt >= MinSampleMs) cpuLastValue = SampleCpu();
            if (cpuLastValue == null) return null;
            return (int)Math.Round(Math.Min(100, Math.Max(0, cpuLastValue.Value)), MidpointRounding.AwayFromZero);
        }

        static double? SampleCpu()
        {
            if (cpuQuery == IntPtr.Zero)
            {
                IntPtr query, counter;
                if (PdhOpenQuery(null, IntPtr.Zero, out query) != 0) return null;
                // Nom anglais : les noms affichés des compteurs suivent la langue de Windows.
                if (PdhAddEnglishCounter(query, @"\Processor Information(_Total)\% Processor Utility", IntPtr.Zero, out counter) != 0)
                {
                    PdhCloseQuery(query);
                    return null;
                }
                cpuQuery = query;
                cpuCounter = counter;
            }
            var now = cpuClock.ElapsedMilliseconds;
            if (PdhCollectQueryData(cpuQuery) != 0) return null;
            var baseline = cpuSampledAt < 0 || now - cpuSampledAt > StaleMs;
            cpuSampledAt = now;
            if (baseline)
            {
                // Simple point de départ : le taux se mesure sur le relevé suivant, pris peu après.
                Thread.Sleep(BaselineMs);
                now = cpuClock.ElapsedMilliseconds;
                if (PdhCollectQueryData(cpuQuery) != 0) return null;
                cpuSampledAt = now;
            }
            PDH_FMT_COUNTERVALUE value;
            if (PdhGetFormattedCounterValue(cpuCounter, PDH_FMT_DOUBLE, IntPtr.Zero, out value) != 0) return null;
            if (value.CStatus != 0 && value.CStatus != 1) return null; // PDH_CSTATUS_VALID_DATA, PDH_CSTATUS_NEW_DATA
            return double.IsNaN(value.DoubleValue) || double.IsInfinity(value.DoubleValue) ? (double?)null : value.DoubleValue;
        }
    }
}
