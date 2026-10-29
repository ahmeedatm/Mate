// Icône du compagnon dans la barre des menus du Mac. Un clic ouvre la page locale ; le clic droit (ou Ctrl + clic)
// montre l'état de la tablette (lu sur GET /local/status) et « Ouvrir les réglages de la tablette ».
// Lancé par le compagnon (src/mac/menubar.ts) avec `--port <port>` ; s'arrête dès que le compagnon qui l'a lancé
// disparaît. Compilé par `npm run build:menubar` en src/mac/UnowhyMenu, gardé dans le dépôt.
import AppKit

let args = CommandLine.arguments
let port = args.firstIndex(of: "--port").flatMap { $0 + 1 < args.count ? Int(args[$0 + 1]) : nil } ?? 3000
let base = "http://127.0.0.1:\(port)/"
let parent = getppid()

struct Status {
  var tablet = false
  var name = "tablette"
  var battery: (level: Int, charging: Bool)?
}

/// Icône Cadran monochrome (docs/ui-ux/da/icone-mono.svg, repère 24 × 24) : image modèle, le système la teinte.
func icon() -> NSImage {
  let image = NSImage(size: NSSize(width: 18, height: 18), flipped: true) { rect in
    let k = rect.width / 24
    NSColor.black.setStroke()
    NSColor.black.setFill()
    func line(_ x1: CGFloat, _ x2: CGFloat, alpha: CGFloat) {
      let path = NSBezierPath()
      path.move(to: NSPoint(x: x1 * k, y: 12 * k))
      path.line(to: NSPoint(x: x2 * k, y: 12 * k))
      path.lineWidth = 2 * k
      path.lineCapStyle = .round
      NSColor.black.withAlphaComponent(alpha).setStroke()
      path.stroke()
    }
    line(2.5, 13.5, alpha: 1)
    line(13.5, 21.5, alpha: 0.4)
    let ring = NSBezierPath(ovalIn: NSRect(x: (13.5 - 4.3) * k, y: (12 - 4.3) * k, width: 8.6 * k, height: 8.6 * k))
    ring.lineWidth = 1.2 * k
    NSColor.black.withAlphaComponent(0.55).setStroke()
    ring.stroke()
    NSColor.black.setFill()
    NSBezierPath(ovalIn: NSRect(x: (13.5 - 2.3) * k, y: (12 - 2.3) * k, width: 4.6 * k, height: 4.6 * k)).fill()
    return true
  }
  image.isTemplate = true
  return image
}

final class Controller: NSObject {
  let item = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
  var status = Status()

  override init() {
    super.init()
    if let button = item.button {
      button.image = icon()
      button.target = self
      button.action = #selector(clicked)
      button.sendAction(on: [.leftMouseUp, .rightMouseUp])
    }
    refresh()
    Timer.scheduledTimer(withTimeInterval: 3, repeats: true) { [weak self] _ in
      // Plus de compagnon : parent mort, repris par launchd.
      if getppid() != parent { NSApp.terminate(nil) }
      self?.refresh()
    }
  }

  func summary() -> String {
    guard status.tablet else { return "Tablette non connectée" }
    return "Tablette connectée · \(status.name)"
  }

  func refresh() {
    guard let url = URL(string: base + "local/status") else { return }
    URLSession.shared.dataTask(with: URLRequest(url: url, timeoutInterval: 2)) { data, _, _ in
      var next = Status()
      if let data, let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
        next.tablet = json["tablet"] as? Bool ?? false
        if let info = json["tabletInfo"] as? [String: Any] {
          next.name = info["name"] as? String ?? next.name
          if let b = info["battery"] as? [String: Any], let level = b["level"] as? Int {
            next.battery = (level, b["charging"] as? Bool ?? false)
          }
        }
      }
      DispatchQueue.main.async {
        self.status = next
        self.item.button?.toolTip = "Compagnon Unowhy · " + self.summary()
      }
    }.resume()
  }

  @objc func clicked() {
    let event = NSApp.currentEvent
    if event?.type == .rightMouseUp || event?.modifierFlags.contains(.control) == true { showMenu() } else { open(base) }
  }

  func showMenu() {
    let menu = NSMenu()
    menu.addItem(withTitle: summary(), action: nil, keyEquivalent: "").isEnabled = false
    if status.tablet, let b = status.battery {
      menu.addItem(withTitle: "Batterie \(b.level) %" + (b.charging ? " (en charge)" : ""), action: nil, keyEquivalent: "").isEnabled = false
    }
    menu.addItem(.separator())
    let settings = NSMenuItem(title: "Ouvrir les réglages de la tablette", action: #selector(openSettings), keyEquivalent: "")
    settings.target = self
    settings.isEnabled = status.tablet
    menu.addItem(settings)
    let page = NSMenuItem(title: "Ouvrir la page du compagnon", action: #selector(openPage), keyEquivalent: "")
    page.target = self
    menu.addItem(page)
    // Le menu n'est posé que le temps du clic : sans lui, le clic gauche reste une action.
    item.menu = menu
    item.button?.performClick(nil)
    item.menu = nil
  }

  @objc func openSettings() { open(base + "?section=tablette") }
  @objc func openPage() { open(base) }
  func open(_ url: String) { if let u = URL(string: url) { NSWorkspace.shared.open(u) } }
}

let app = NSApplication.shared
app.setActivationPolicy(.accessory)
let controller = Controller()
app.run()
