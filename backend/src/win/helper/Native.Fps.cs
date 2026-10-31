using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;

namespace Mate
{
    public static partial class Native
    {
        // --- Images par seconde du jeu au premier plan (widget Performances). Lues par ADLX, le SDK d'AMD : le même
        // chiffre que l'overlay d'AMD Software: Adrenalin Edition. La DLL (amdadlx64.dll) est installée avec le pilote
        // AMD : on la charge à l'exécution et on appelle les tables de fonctions de ses interfaces COM-like, sans rien
        // livrer. Sans carte AMD (DLL absente) : null, le widget n'a pas de FPS. Indices des fonctions : SDK/Include
        // d'ADLX 2.0.0.125 (SDK/Include/ISystem.h, IPerformanceMonitoring.h), tables « Vtbl » en C ; vérifiés sur un PC à
        // carte AMD (RX 6750 XT) : 224 à 277 FPS dans un jeu au premier plan.

        const ulong AdlxVersion = 2UL << 48 | 125UL; // ADLX 2.0.0.125, celui des en-têtes lus
        const int AdlxOk = 0;
        const int AdlxSystemGetPerformanceMonitoringServices = 9;
        const int AdlxRelease = 1;
        const int AdlxServicesGetCurrentFps = 20;
        const int AdlxFpsValue = 4;
        /** Un pilote qui démarre (ou installé plus tard) est réessayé, sans solliciter la DLL à chaque seconde. */
        const int AdlxRetryMs = 30000;

        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        static extern IntPtr LoadLibraryW(string path);

        [DllImport("kernel32.dll", CharSet = CharSet.Ansi)]
        static extern IntPtr GetProcAddress(IntPtr module, string name);

        [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
        delegate int AdlxInitializeFn(ulong version, out IntPtr system);

        [UnmanagedFunctionPointer(CallingConvention.StdCall)]
        delegate int AdlxGetInterfaceFn(IntPtr self, out IntPtr result);

        [UnmanagedFunctionPointer(CallingConvention.StdCall)]
        delegate int AdlxReleaseFn(IntPtr self);

        [UnmanagedFunctionPointer(CallingConvention.StdCall)]
        delegate int AdlxIntFn(IntPtr self, out int value);

        // shortcut: l'objet système d'ADLX et la DLL restent chargés jusqu'à la fin du pilote (ni Release ni ADLXTerminate),
        // à revoir si le pilote doit un jour se fermer avant le compagnon ; version minimale : celle des en-têtes, un pilote
        // plus ancien est lu comme « pas de lecteur ».
        static IntPtr adlxSystem;
        static IntPtr adlxServices;
        static int adlxTriedAt;
        static bool adlxTried;

        /** Fonction numéro `index` de la table d'un objet ADLX (premier champ de l'objet = pointeur de table). */
        static T AdlxMethod<T>(IntPtr obj, int index) where T : class
        {
            IntPtr table = Marshal.ReadIntPtr(obj);
            return (T)(object)Marshal.GetDelegateForFunctionPointer(Marshal.ReadIntPtr(table, index * IntPtr.Size), typeof(T));
        }

        /** Services de mesure d'ADLX, ouverts une fois ; null si la DLL ou le pilote n'est pas là (réessayé plus tard). */
        static IntPtr AdlxPerformanceServices()
        {
            if (adlxServices != IntPtr.Zero) return adlxServices;
            if (adlxTried && Environment.TickCount - adlxTriedAt < AdlxRetryMs) return IntPtr.Zero;
            adlxTried = true;
            adlxTriedAt = Environment.TickCount;
            // Chemin complet du dossier système : pas de recherche de DLL dans un dossier modifiable.
            // ADLX n'est initialisée qu'une fois : réessayer seulement l'étape qui a échoué (pas de fuite à chaque essai).
            if (adlxSystem == IntPtr.Zero)
            {
                IntPtr dll = LoadLibraryW(System.IO.Path.Combine(Environment.SystemDirectory, "amdadlx64.dll"));
                if (dll == IntPtr.Zero) return IntPtr.Zero;
                IntPtr init = GetProcAddress(dll, "ADLXInitialize");
                if (init == IntPtr.Zero) return IntPtr.Zero;
                IntPtr system;
                if (((AdlxInitializeFn)Marshal.GetDelegateForFunctionPointer(init, typeof(AdlxInitializeFn)))(AdlxVersion, out system) != AdlxOk) return IntPtr.Zero;
                adlxSystem = system;
            }
            IntPtr services;
            if (AdlxMethod<AdlxGetInterfaceFn>(adlxSystem, AdlxSystemGetPerformanceMonitoringServices)(adlxSystem, out services) != AdlxOk) return IntPtr.Zero;
            adlxServices = services;
            return services;
        }

        /**
         * FPS du jeu en cours : `{ fps }` avec le nombre d'images par seconde, ou `fps` nul quand aucun jeu n'est
         * mesuré (ADLX rend 0 ou « non pris en charge »). null : pas de lecteur sur cette machine (pas de carte AMD).
         */
        static object Fps()
        {
            IntPtr services = AdlxPerformanceServices();
            if (services == IntPtr.Zero) return null;
            object fps = null;
            IntPtr sample;
            if (AdlxMethod<AdlxGetInterfaceFn>(services, AdlxServicesGetCurrentFps)(services, out sample) != AdlxOk || sample == IntPtr.Zero)
            {
                // Services devenus inutilisables (pilote redémarré ou mis à jour) : rouverts au prochain essai.
                adlxServices = IntPtr.Zero;
                adlxTried = false;
                return null;
            }
            try
            {
                int value;
                if (AdlxMethod<AdlxIntFn>(sample, AdlxFpsValue)(sample, out value) == AdlxOk && value > 0) fps = value;
            }
            finally
            {
                AdlxMethod<AdlxReleaseFn>(sample, AdlxRelease)(sample);
            }
            return new Dictionary<string, object> { { "fps", fps } };
        }
    }
}
