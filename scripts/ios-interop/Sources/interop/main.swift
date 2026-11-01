// Usage: interop export <out-dir>   writes iOS-encoded files (appearance, skin, candidate area, layouts, catalog)
//        interop verify <in-dir>    decodes every interop file in <in-dir> with the iOS decoders; exit 1 on any failure
import AppearanceKit
import CandidateKit
import Foundation
import LayoutKit
import SkinKit

func write(_ data: Data, _ dir: URL, _ name: String) throws {
    try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    try data.write(to: dir.appendingPathComponent(name), options: .atomic)
}

func export(to out: URL) throws {
    // Appearances: the built-in presets as the iOS app exports them, plus one with a customised candidate area.
    for document in AppearanceDocument.builtinPresets + [AppearanceDocument.systemDefault] {
        try write(document.encoded(), out, "appearance-\(document.id).songyanappearance.json")
    }
    var bar = CandidateBarDefinition(id: "grid-bar", name: "三列网格")
    bar.collapsed.height = 52
    bar.collapsed.cellPaddingX = 14
    bar.collapsed.cellSpacing = 6
    bar.expanded.mode = .grid
    bar.expanded.columns = 3
    bar.expanded.rowSpacing = 4
    bar.expanded.columnSpacing = 2
    bar.controls.pagePosition = .leading
    let custom = AppearanceDocument(
        id: "grid-look", name: "网格外观", appearance: .dark,
        skin: AppearanceDocument.builtinPresets[1].skin, candidateBar: bar)
    try write(custom.encoded(), out, "appearance-grid-look.songyanappearance.json")
    try write(bar.encoded(), out, "candidate-grid-bar.songyancand.json")
    try write(AppearanceDocument.builtinPresets[2].skin.encoded(), out, "skin-\(AppearanceDocument.builtinPresets[2].id).songyanskin.json")

    // Layouts: each built-in on its own, a custom one with a split, and a catalog with a custom page and pairing.
    for layout in BuiltinLayouts.all {
        try write(layout.encoded(), out, "layout-\(layout.id).json")
    }
    var object = try JSONSerialization.jsonObject(with: BuiltinLayouts.qwerty26.encoded()) as! [String: Any]
    object["id"] = "custom-qwerty"
    object["name"] = "自定义 26 键"
    object["split"] = ["activation": "always", "rowBreaks": [5, 5, 3], "gapFraction": 0.2, "automaticGap": false] as [String: Any]
    let customLayout = try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys, .prettyPrinted])
    try write(customLayout, out, "layout-custom-qwerty.json")

    // A nine-key layout authored on iOS: its own faces, its own rails and an edited control row
    // (the control row is not drawn on Android, but it must come back out unchanged).
    var phone = try JSONSerialization.jsonObject(with: BuiltinLayouts.phone9.encoded()) as! [String: Any]
    phone["id"] = "custom-phone9"
    phone["name"] = "我的九键"
    var phoneRows = phone["rows"] as! [[String: Any]]
    var gridKeys = phoneRows[1]["keys"] as! [[String: Any]]
    gridKeys[1]["label"] = "我的"
    phoneRows[1]["keys"] = gridKeys
    var control = phoneRows[3]["keys"] as! [[String: Any]]
    control[1]["label"] = "空 格"
    control[1]["longPress"] = [["label": "，", "action": ["type": "literal", "value": "，"]]]
    phoneRows[3]["keys"] = control
    phone["rows"] = phoneRows
    func railKey(_ label: String, _ action: [String: Any], span: Double? = nil) -> [String: Any] {
        var key: [String: Any] = ["label": label, "action": action]
        if let span { key["width"] = span }
        return key
    }
    phone["auxiliaryAreas"] = [
        [
            "position": "leading", "role": "punctuation",
            "keys": [
                railKey("。", ["type": "literal", "value": "。"]),
                railKey("？", ["type": "literal", "value": "？"]),
            ],
        ],
        ["position": "leading", "role": "disambiguation", "keys": [] as [Any]],
        [
            "position": "trailing", "role": "editing",
            "keys": [
                railKey("⌫", ["type": "backspace"]),
                railKey("重输", ["type": "clearInput"], span: 2),
            ],
        ],
    ]
    let customPhone = try JSONSerialization.data(withJSONObject: phone, options: [.sortedKeys, .prettyPrinted])
    _ = try LayoutDefinition.decode(customPhone)
    try write(customPhone, out, "layout-custom-phone9.json")

    var registry = try LayoutRegistry()
    try registry.install(customLayout)
    let page = KeyboardPageDefinition(
        id: "custom-numeric", name: "我的数字页", kind: .numeric,
        rows: KeyboardPagePresets.numericRowsPage(ascii: false).rows, defaultSymbolMarks: .chinese)
    try registry.savePage(page)
    try registry.setComposition(
        KeyboardComposition(mainLayoutID: "custom-qwerty", numericPageID: "custom-numeric"),
        for: "songyan_quanpin:qwerty26")
    let store = LayoutStore(directoryURL: out.appendingPathComponent("catalog"))
    try store.save(registry)
}

func verify(in dir: URL) throws -> Bool {
    var ok = true
    let names = try FileManager.default.contentsOfDirectory(atPath: dir.path).sorted()
    for name in names {
        let url = dir.appendingPathComponent(name)
        do {
            let data = try Data(contentsOf: url)
            if name.hasSuffix(".songyanappearance.json") || name.hasSuffix(".songyanskin.json")
                || name.hasSuffix(".songyancand.json")
            {
                _ = try AppearanceDocument.decodeImported(data)
            } else if name.hasPrefix("layout-") {
                _ = try LayoutDefinition.decode(data)
            } else if name == "layouts.json" {
                _ = try LayoutStore(directoryURL: dir).load()
            } else if name.hasPrefix("catalog-") {
                let registry = try JSONDecoder().decode(LayoutRegistry.self, from: data)
                try registry.validate()
            } else {
                continue
            }
            print("ok   \(name)")
        } catch {
            ok = false
            print("FAIL \(name): \(error)")
        }
    }
    return ok
}

let arguments = CommandLine.arguments
guard arguments.count == 3 else {
    print("usage: interop export|verify <dir>")
    exit(2)
}
let directory = URL(fileURLWithPath: arguments[2])
switch arguments[1] {
case "export": try export(to: directory)
case "verify": exit(try verify(in: directory) ? 0 : 1)
default: exit(2)
}
