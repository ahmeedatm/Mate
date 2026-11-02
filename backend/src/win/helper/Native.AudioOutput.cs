using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;

namespace Mate
{
    public static partial class Native
    {
        // --- Choix de la sortie audio : liste des sorties actives, et sortie par défaut (IPolicyConfig)

        // Interface non documentée mais stable depuis Windows Vista (utilisée par le panneau Son et les outils de bascule) :
        // seul SetDefaultEndpoint sert, les autres méthodes ne sont là que pour tenir la place dans la table.
        [ComImport, Guid("F8679F50-850A-41CF-9C72-430F290290C8"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        interface IPolicyConfig
        {
            [PreserveSig] int GetMixFormat(IntPtr id, IntPtr format);
            [PreserveSig] int GetDeviceFormat(IntPtr id, int isDefault, IntPtr format);
            [PreserveSig] int ResetDeviceFormat(IntPtr id);
            [PreserveSig] int SetDeviceFormat(IntPtr id, IntPtr endpointFormat, IntPtr mixFormat);
            [PreserveSig] int GetProcessingPeriod(IntPtr id, int isDefault, IntPtr defaultPeriod, IntPtr minimumPeriod);
            [PreserveSig] int SetProcessingPeriod(IntPtr id, IntPtr period);
            [PreserveSig] int GetShareMode(IntPtr id, IntPtr mode);
            [PreserveSig] int SetShareMode(IntPtr id, IntPtr mode);
            [PreserveSig] int GetPropertyValue(IntPtr id, IntPtr key, IntPtr value);
            [PreserveSig] int SetPropertyValue(IntPtr id, IntPtr key, IntPtr value);
            [PreserveSig] int SetDefaultEndpoint([MarshalAs(UnmanagedType.LPWStr)] string id, int role);
            [PreserveSig] int SetEndpointVisibility(IntPtr id, int visible);
        }

        [ComImport, Guid("870AF99C-171D-4F9E-AF0D-E63DF40C2BC9")]
        class PolicyConfigClient { }

        /** Nom d'une sortie comme dans le panneau Son (« Haut-parleurs (Realtek Audio) »), son identifiant à défaut. */
        static string DeviceName(IMMDevice device, string fallback)
        {
            IPropertyStore store = null;
            try
            {
                if (device.OpenPropertyStore(0, out store) != 0 || store == null) return fallback; // STGM_READ
                var key = new PROPERTYKEY();
                key.fmtid = new Guid("A45C254E-DF1C-4EFD-8020-67D146A850E0"); // PKEY_Device_FriendlyName
                key.pid = 14;
                PROPVARIANT v;
                if (store.GetValue(ref key, out v) != 0) return fallback;
                string name = v.vt == 31 ? Marshal.PtrToStringUni(v.p) : null; // VT_LPWSTR
                PropVariantClear(ref v);
                return string.IsNullOrWhiteSpace(name) ? fallback : name;
            }
            catch (Exception) { return fallback; }
            finally { if (store != null) Marshal.ReleaseComObject(store); }
        }

        /** Sorties actives (haut-parleurs, casque, carte son) ; `current` : la sortie par défaut du système. */
        static object AudioOutputs()
        {
            var enumerator = (IMMDeviceEnumerator)new MMDeviceEnumerator();
            string currentId = null;
            IMMDevice defaultDevice;
            if (enumerator.GetDefaultAudioEndpoint(0, 0, out defaultDevice) == 0 && defaultDevice != null) defaultDevice.GetId(out currentId);

            var list = new List<Dictionary<string, object>>();
            IMMDeviceCollection devices;
            uint count;
            if (enumerator.EnumAudioEndpoints(0, 1, out devices) != 0 || devices == null || devices.GetCount(out count) != 0) return list; // DEVICE_STATE_ACTIVE
            for (uint i = 0; i < count; i++)
            {
                IMMDevice device;
                string id;
                if (devices.Item(i, out device) != 0 || device == null || device.GetId(out id) != 0 || id == null) continue;
                var output = new Dictionary<string, object>();
                output["id"] = id;
                output["name"] = DeviceName(device, id);
                output["current"] = id == currentId;
                list.Add(output);
                Marshal.ReleaseComObject(device);
            }
            Marshal.ReleaseComObject(devices);
            return list;
        }

        /** Sortie par défaut pour les sons et le multimédia (pas celle des appels : le casque de réunion reste le sien). */
        static object SetAudioOutput(string id)
        {
            var policy = (IPolicyConfig)new PolicyConfigClient();
            int hr = policy.SetDefaultEndpoint(id, 0); // eConsole
            if (hr == 0) hr = policy.SetDefaultEndpoint(id, 1); // eMultimedia
            Marshal.ReleaseComObject(policy);
            if (hr != 0) throw new InvalidOperationException("Windows a refusé de changer la sortie audio (0x" + hr.ToString("X8") + ")");
            return AudioOutputs();
        }
    }
}
