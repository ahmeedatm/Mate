using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Reflection;
using System.Runtime.InteropServices;

namespace Companion
{
    public static partial class Native
    {
        // --- Applis : dossier virtuel "Applications" (celui du menu Démarrer, applis classiques et du Store)

        static List<object> Apps()
        {
            var list = new List<object>();
            object shell = Activator.CreateInstance(Type.GetTypeFromProgID("Shell.Application"));
            try
            {
                object folder = Call(shell, "NameSpace", "shell:::{4234d49b-0245-4df3-b780-3893943456e1}");
                object items = Call(folder, "Items");
                int count = Convert.ToInt32(Get(items, "Count"));
                for (int i = 0; i < count; i++)
                {
                    object item = Call(items, "Item", i);
                    if (item == null) continue;
                    var d = new Dictionary<string, object>();
                    d["name"] = Convert.ToString(Get(item, "Name"));
                    d["id"] = Convert.ToString(Get(item, "Path"));
                    list.Add(d);
                    Marshal.ReleaseComObject(item);
                }
            }
            finally { Marshal.ReleaseComObject(shell); }
            return list;
        }

        static object Call(object o, string name, params object[] args)
        {
            return o.GetType().InvokeMember(name, BindingFlags.InvokeMethod, null, o, args);
        }

        static object Get(object o, string name)
        {
            return o.GetType().InvokeMember(name, BindingFlags.GetProperty, null, o, null);
        }

        static void Open(string target)
        {
            var psi = new ProcessStartInfo(target);
            psi.UseShellExecute = true;
            Process.Start(psi);
        }
    }
}
