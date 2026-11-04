// Autorisations du Mac (TCC) du compagnon, pour l'écran « Autorisations du Mac » de la page locale (src/mac/permissions.ts).
// Lancé par node, donc attribué à l'agent Mate (portable/mac/agent) : c'est lui que macOS nomme dans ses fenêtres.
// Compilé par `npm run build:permissions` en src/mac/MatePermissions, gardé dans le dépôt. Sortie : un objet JSON.
//
//   status <bundleId>…          accessibilité, enregistrement de l'écran et, pour chaque appli, Automation :
//                               "granted" | "denied" | "not-asked" | "closed" (appli fermée : -600, pas de réponse possible).
//                               Ne demande rien à l'utilisateur.
//   request-automation <id>     lance l'appli en arrière-plan si besoin, puis demande l'Automation (fenêtre de macOS : la
//                               commande attend le choix de l'utilisateur).
//   request-screen              demande l'enregistrement de l'écran.
import AppKit
import ApplicationServices
import CoreGraphics
import Foundation

func print(json: [String: Any]) {
  guard let data = try? JSONSerialization.data(withJSONObject: json, options: [.sortedKeys]),
    let text = String(data: data, encoding: .utf8)
  else { return }
  Swift.print(text)
}

/// Autorisation d'envoyer des événements Apple à `bundleId`. -1743 refusé, -1744 pas encore demandé, -600 appli fermée.
func automation(_ bundleId: String, ask: Bool) -> String {
  let target = NSAppleEventDescriptor(bundleIdentifier: bundleId)
  guard let desc = target.aeDesc else { return "closed" }
  // typeWildCard : toute classe et tout événement.
  let status = AEDeterminePermissionToAutomateTarget(desc, AEEventClass(0x2A2A_2A2A), AEEventID(0x2A2A_2A2A), ask)
  switch status {
  case 0: return "granted"
  case -1743: return "denied"
  case -1744: return "not-asked"
  default: return "closed"
  }
}

/// Lance l'appli sans la mettre devant, puis attend qu'elle tourne (6 s au plus).
func launchInBackground(_ bundleId: String) {
  func running() -> Bool { !NSRunningApplication.runningApplications(withBundleIdentifier: bundleId).isEmpty }
  if running() { return }
  guard let url = NSWorkspace.shared.urlForApplication(withBundleIdentifier: bundleId) else { return }
  let configuration = NSWorkspace.OpenConfiguration()
  configuration.activates = false
  configuration.hides = true
  configuration.addsToRecentItems = false
  NSWorkspace.shared.openApplication(at: url, configuration: configuration) { _, _ in }
  for _ in 0..<30 where !running() { Thread.sleep(forTimeInterval: 0.2) }
  Thread.sleep(forTimeInterval: 0.5)
}

let args = Array(CommandLine.arguments.dropFirst())
switch args.first {
case "status":
  var apps: [String: String] = [:]
  for id in args.dropFirst() { apps[id] = automation(id, ask: false) }
  print(json: [
    "accessibility": AXIsProcessTrusted(),
    "screen": CGPreflightScreenCaptureAccess(),
    "automation": apps,
  ])
case "request-automation" where args.count == 2:
  launchInBackground(args[1])
  print(json: ["automation": automation(args[1], ask: true)])
case "request-screen":
  print(json: ["screen": CGRequestScreenCaptureAccess()])
default:
  FileHandle.standardError.write(Data("Usage : MatePermissions status <bundleId>… | request-automation <bundleId> | request-screen\n".utf8))
  exit(2)
}
