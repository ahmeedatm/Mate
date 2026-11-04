// Lanceur et agent de Mate sur Mac : un seul binaire universel, deux rôles. Compilé par `npm run build:launcher`
// (swiftc, signature ad hoc), gardé dans le dépôt : le job CI package-mac tourne sous Linux.
//
// ATTENTION : toute modification de ce fichier, de l'Info.plist ou de l'icône de l'agent (portable/mac/agent) change son
// empreinte (cdhash). macOS lie chaque autorisation d'un programme sans signature Developer ID à cette empreinte : tous
// les Mac devront redonner Accessibilité, Automation, etc. À ne changer que pour une vraie raison. Le test
// backend/test/launcher-agent.test.ts échoue tant que `npm run build:launcher` n'a pas été relancé.
//
// Pourquoi un agent : macOS (TCC) attribue une demande d'autorisation au « processus responsable ». Un enfant hérite du
// responsable de son parent ; une appli lancée par LaunchServices (`open`) est son propre responsable. Mate.app change
// à chaque version (backend dans Resources, version dans Info.plist) : elle ne peut pas porter les autorisations. Elle
// lance donc l'agent, un petit bundle figé (octets identiques d'une version à l'autre) recopié dans le runtime, qui lance
// node. Les fenêtres de macOS disent « Mate » avec l'icône, pas « node ».
//
//   Rôle APPLI  (Mate.app/Contents/MacOS/Mate : Finder, `open`, qa/install-mac.sh, agent de session `--demarrage`)
//     recopie le runtime dans ~/Library/Application Support/Mate/runtime, relance un compagnon d'une autre version,
//     lance l'agent par LaunchServices (`open -n -g`), attend /health, ouvre la page locale.
//   Rôle AGENT  (<runtime>/Mate.app/Contents/MacOS/Mate, identifiant app.mate.agent)
//     lance node en enfant et le relance s'il s'arrête. Sortie du serveur : 75 = quitter, 76 = quitter et effacer le
//     runtime (« tout oublier »), autre = relancer après 10 s.
//
// Variables transmises à l'agent par le fichier <runtime>/.agent-env (lignes NOM=valeur, écrit juste avant l'ouverture) :
// `open --env` n'est pas garanti sur macOS 11. COMPANION_HEADLESS=1 (qa/install-mac.sh) : ni page ni boîte.
import Foundation

let agentIdentifier = "app.mate.agent"
let environment = ProcessInfo.processInfo.environment
let arguments = Array(CommandLine.arguments.dropFirst())
let files = FileManager.default

#if arch(arm64)
let arch = "arm64"
let otherArch = "x64"
#else
let arch = "x64"
let otherArch = "arm64"
#endif

/// Variables que l'appli donne à l'agent, puis au serveur, quand elles sont posées.
let forwarded = [
  "PORT", "HOST", "DATA_DIR", "DOCS_DIR", "COMPANION_LOG_DIR", "COMPANION_RUNTIME_DIR", "COMPANION_HEADLESS", "COMPANION_LABEL",
  "NO_ALERT",
]

func say(_ text: String) {
  FileHandle.standardError.write(Data((text + "\n").utf8))
}

@discardableResult
func run(_ path: String, _ args: [String]) -> (status: Int32, output: String) {
  let process = Process()
  process.executableURL = URL(fileURLWithPath: path)
  process.arguments = args
  let pipe = Pipe()
  process.standardOutput = pipe
  process.standardError = pipe
  process.standardInput = FileHandle.nullDevice
  do { try process.run() } catch { return (127, "\(error)") }
  let data = pipe.fileHandleForReading.readDataToEndOfFile()
  process.waitUntilExit()
  return (process.terminationStatus, String(decoding: data, as: UTF8.self))
}

func sleep(_ seconds: Double) { Thread.sleep(forTimeInterval: seconds) }

// MARK: - Rôle AGENT

/// Boucle du serveur. Ne rend jamais la main : sort avec le code du serveur (75, 76) ou à la réception de SIGTERM.
func agentRole() -> Never {
  let runtime = URL(fileURLWithPath: Bundle.main.bundlePath).resolvingSymlinksInPath().deletingLastPathComponent().path
  var vars = environment
  if let text = try? String(contentsOfFile: runtime + "/.agent-env", encoding: .utf8) {
    for line in text.split(separator: "\n") {
      guard let eq = line.firstIndex(of: "=") else { continue }
      vars[String(line[..<eq])] = String(line[line.index(after: eq)...])
    }
  }
  vars["PATH"] = "/usr/bin:/bin:/usr/sbin:/sbin"
  let logs = vars["COMPANION_LOG_DIR"] ?? NSHomeDirectory() + "/Library/Logs/Mate"
  try? files.createDirectory(atPath: logs, withIntermediateDirectories: true)
  let logFile = logs + "/backend.log"
  if !files.fileExists(atPath: logFile) { files.createFile(atPath: logFile, contents: nil) }

  let lock = NSLock()
  var child: Process?
  var stopping = false

  signal(SIGHUP, SIG_IGN)
  signal(SIGTERM, SIG_IGN)
  let term = DispatchSource.makeSignalSource(signal: SIGTERM, queue: .main)
  term.setEventHandler {
    lock.lock()
    stopping = true
    let running = child
    lock.unlock()
    if let running, running.isRunning { running.terminate() } else { exit(0) }
  }
  term.resume()

  let node = ["node/darwin-\(arch)/Mate", "node/darwin-\(otherArch)/Mate"]
    .map { runtime + "/" + $0 }
    .first { files.isExecutableFile(atPath: $0) } ?? runtime + "/node/darwin-\(arch)/Mate"

  Thread.detachNewThread {
    while true {
      let process = Process()
      process.executableURL = URL(fileURLWithPath: node)
      process.arguments = ["--env-file-if-exists=.env", "dist/server.mjs"]
      process.currentDirectoryURL = URL(fileURLWithPath: runtime)
      process.environment = vars
      process.standardInput = FileHandle.nullDevice
      if let out = FileHandle(forWritingAtPath: logFile) {
        out.seekToEndOfFile()
        process.standardOutput = out
        process.standardError = out
      }
      var code: Int32 = 1
      lock.lock()
      let mustStop = stopping
      lock.unlock()
      if mustStop { exit(0) }
      do {
        try process.run()
        lock.lock()
        child = process
        let late = stopping
        lock.unlock()
        if late { process.terminate() }
        process.waitUntilExit()
        code = process.terminationStatus
      } catch {
        say("Mate : \(node) ne démarre pas : \(error)")
      }
      lock.lock()
      child = nil
      let wasStopping = stopping
      lock.unlock()
      if wasStopping { exit(0) }
      switch code {
      case 75: exit(0)
      case 76:
        files.changeCurrentDirectoryPath("/")
        try? files.removeItem(atPath: runtime)
        exit(0)
      default: break
      }
      for _ in 0..<20 {
        sleep(0.5)
        lock.lock()
        let now = stopping
        lock.unlock()
        if now { exit(0) }
      }
    }
  }
  dispatchMain()
}

// MARK: - Rôle APPLI

func appRole() -> Never {
  let mode = arguments.first ?? ""
  let app = Bundle.main.bundleURL.resolvingSymlinksInPath().path
  let source = app + "/Contents/Resources/backend"
  let runtime = environment["COMPANION_RUNTIME_DIR"] ?? NSHomeDirectory() + "/Library/Application Support/Mate/runtime"
  let logs = environment["COMPANION_LOG_DIR"] ?? NSHomeDirectory() + "/Library/Logs/Mate"
  let port = environment["PORT"] ?? "3000"
  let headless = !(environment["COMPANION_HEADLESS"] ?? "").isEmpty
  let agentApp = runtime + "/Mate.app"
  let node = runtime + "/node/darwin-\(arch)/Mate"

  func alert(_ message: String) {
    if headless { return say(message) }
    run(
      "/usr/bin/osascript",
      [
        "-e",
        "on run argv\n display dialog (item 1 of argv) with title \"Mate\" buttons {\"OK\"} default button 1 with icon caution\nend run",
        message,
      ])
  }
  func curl(_ args: [String], timeout: String, path: String) -> Bool {
    run("/usr/bin/curl", ["-fsS", "--max-time", timeout] + args + ["http://127.0.0.1:\(port)\(path)"]).status == 0
  }
  func healthy() -> Bool { curl([], timeout: "2", path: "/health") }
  func openPage() {
    if !headless && mode != "--demarrage" { run("/usr/bin/open", ["http://127.0.0.1:\(port)/"]) }
  }
  func sameBuild() -> Bool { files.contentsEqual(atPath: source + "/runtime.stamp", andPath: runtime + "/runtime.stamp") }
  func waitDown(_ seconds: Int) -> Bool {
    for _ in 0..<(seconds * 2) {
      if !healthy() { return true }
      sleep(0.5)
    }
    return !healthy()
  }

  // Reste d'une mise à jour faite par le panneau.
  try? files.removeItem(atPath: app + ".old")

  if healthy() {
    if sameBuild() { openPage(); exit(0) }
    // Autre build : on lui demande de quitter. Un compagnon lancé depuis le dépôt n'est jamais touché.
    _ = curl(["-X", "POST", "-H", "content-type: application/json", "-d", "{}"], timeout: "3", path: "/local/quit")
    if !waitDown(25) {
      alert("Un compagnon tourne déjà sur le port \(port) (autre version, ou lancé depuis le dépôt) : arrête-le, puis rouvre l'appli.")
      exit(1)
    }
  }

  if !sameBuild() || !files.isExecutableFile(atPath: node) || !files.isExecutableFile(atPath: agentApp + "/Contents/MacOS/Mate") {
    do { try files.createDirectory(atPath: runtime, withIntermediateDirectories: true) } catch { exit(1) }
    // Sans l'autre architecture de node ; .env et .cache gardés ; le repère est posé en dernier.
    var out = run(
      "/usr/bin/rsync",
      [
        "-a", "--delete", "--exclude", "/node/darwin-\(otherArch)", "--exclude", "/.env", "--exclude", "/.cache",
        "--exclude", "/.agent-env", "--exclude", "/runtime.stamp", source + "/", runtime + "/",
      ])
    if out.status == 0 {
      try? files.removeItem(atPath: runtime + "/runtime.stamp")
      do { try files.copyItem(atPath: source + "/runtime.stamp", toPath: runtime + "/runtime.stamp") } catch { out = (1, "\(error)") }
    }
    if out.status != 0 {
      let tail = out.output.split(separator: "\n").suffix(6).joined(separator: "\n")
      alert("Le compagnon n'a pas pu être préparé dans \(runtime).\n\n\(tail)")
      exit(1)
    }
    // Fichiers sortis d'un zip téléchargé : sans ça, macOS demande une confirmation pour l'agent et node.
    run("/usr/bin/xattr", ["-dr", "com.apple.quarantine", app, runtime])
  }

  // Variables de cette session pour l'agent : fichier lu à son démarrage (voir l'en-tête).
  var lines = ["COMPANION_APP=" + app]
  for name in forwarded { if let value = environment[name], !value.isEmpty { lines.append("\(name)=\(value)") } }
  let envFile = runtime + "/.agent-env"
  files.createFile(atPath: envFile, contents: Data((lines.joined(separator: "\n") + "\n").utf8), attributes: [.posixPermissions: 0o600])

  // -n : toujours une nouvelle instance, jamais celle d'un autre compagnon déjà ouvert (le vrai, pendant un essai). -g : sans passer devant.
  let launched = run("/usr/bin/open", ["-n", "-g", agentApp])
  if launched.status != 0 {
    alert("Le compagnon n'a pas pu être lancé : \(launched.output.trimmingCharacters(in: .whitespacesAndNewlines))")
    exit(1)
  }
  if mode == "--demarrage" { exit(0) }

  for _ in 0..<60 {
    if healthy() { break }
    sleep(0.5)
  }
  if !healthy() {
    alert("Le compagnon ne répond pas. Journal : \(logs)/backend.log")
    exit(1)
  }
  openPage()
  exit(0)
}

if Bundle.main.bundleIdentifier == agentIdentifier { agentRole() } else { appRole() }
