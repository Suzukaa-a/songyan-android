// Usage: interop export <out-dir>   writes iOS-encoded files (appearance, skin, candidate area, layouts, catalog)
//        interop verify <in-dir>    decodes every interop file in <in-dir> with the iOS decoders; exit 1 on any failure
//        interop dump <in-dir> <out-dir>
//            decodes every layout file (and catalog) in <in-dir> and writes, per input file, a canonical
//            semantic dump `<name>.dump.json` (rows, keys, actions, widths, splits, rails) and the iOS
//            encoding of what it decoded `<name>.enc.json`. Driven by scripts/check-layout-interop.sh.
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

    // A layout with its own split arrangement: y, g and v repeat on both halves. Encoded by the iOS encoder
    // after decoding, so the sample is what iOS writes.
    var splitObject = try JSONSerialization.jsonObject(with: BuiltinLayouts.qwerty26.encoded()) as! [String: Any]
    splitObject["id"] = "custom-split-keys"
    splitObject["name"] = "拆分键"
    let splitRows = splitObject["rows"] as! [[String: Any]]
    func codeKey(_ letter: String) -> [String: Any] {
        ["label": letter, "action": ["type": "code", "value": letter] as [String: Any]]
    }
    var halves: [[String: Any]] = []
    for (index, cut) in [5, 5, 4].enumerated() {
        let keys = splitRows[index]["keys"] as! [[String: Any]]
        halves.append(["left": Array(keys[..<cut]), "right": Array(keys[cut...])])
    }
    var firstRow = halves[0]; firstRow["left"] = (firstRow["left"] as! [[String: Any]]) + [codeKey("y")]; halves[0] = firstRow
    var secondRow = halves[1]; secondRow["right"] = [codeKey("g")] + (secondRow["right"] as! [[String: Any]]); halves[1] = secondRow
    var thirdRow = halves[2]; thirdRow["right"] = [codeKey("v")] + (thirdRow["right"] as! [[String: Any]]); halves[2] = thirdRow
    let controlKeys = splitRows[3]["keys"] as! [[String: Any]]
    let spaceAt = controlKeys.firstIndex { ($0["action"] as! [String: Any])["type"] as? String == "space" }!
    var halfSpace = controlKeys[spaceAt]
    halfSpace["width"] = ((controlKeys[spaceAt]["width"] as? Double) ?? 1.0) / 2
    halves.append(["left": Array(controlKeys[..<spaceAt]) + [halfSpace], "right": [halfSpace] + Array(controlKeys[(spaceAt + 1)...])])
    splitObject["split"] = [
        "activation": "always", "rowBreaks": [] as [Int], "gapFraction": 0.32, "automaticGap": true, "halves": halves,
    ] as [String: Any]
    let splitLayout = try LayoutDefinition.decode(try JSONSerialization.data(withJSONObject: splitObject))
    try write(splitLayout.encoded(), out, "layout-custom-split-keys.json")

    // A nine-key layout authored on iOS: its own faces, its own rails and an edited control row
    // (the control row is not drawn on Android, but it must come back out unchanged).
    var phone = try JSONSerialization.jsonObject(with: BuiltinLayouts.phone9.encoded()) as! [String: Any]
    phone["id"] = "custom-phone9"
    phone["name"] = "我的九键"
    phone["t9QuickSymbols"] = "。？！"
    phone["t9FirstKeyBehavior"] = "custom"
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
    let customPhone = try LayoutDefinition.decode(try JSONSerialization.data(withJSONObject: phone))
    try write(customPhone.encoded(), out, "layout-custom-phone9.json")

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
                if name == "catalog-android-authored.json" {
                    let nine = registry.layouts.first { $0.id == "android-nine" }
                    guard nine?.t9FirstKeyBehavior == .quickSymbols, nine?.t9QuickSymbols == "，。？" else {
                        throw NSError(domain: "Interop", code: 1, userInfo: [
                            NSLocalizedDescriptionKey: "Nine-key behavior or shortcut order changed during import"
                        ])
                    }
                }
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

// MARK: - Canonical dump

typealias Dump = [String: Any]

func dumpAction(_ action: LayoutAction) -> Dump {
    var d: Dump = ["type": action.type.rawValue]
    if let value = action.value { d["value"] = value }
    if let ascii = action.asciiValue { d["asciiValue"] = ascii }
    return d
}

func dumpKey(_ key: LayoutKey) -> Dump {
    var d: Dump = ["label": key.label, "width": key.widthWeight, "action": dumpAction(key.action)]
    if let secondary = key.secondaryLabel { d["secondaryLabel"] = secondary }
    if let mode = key.titleMode { d["titleMode"] = mode.rawValue }
    if let options = key.longPress {
        d["longPress"] = options.map { ["label": $0.label, "action": dumpAction($0.action)] as Dump }
    }
    return d
}

func dumpSplit(_ split: LayoutSplitConfiguration) -> Dump {
    var s: Dump = [
        "activation": split.activation.rawValue, "rowBreaks": split.rowBreaks,
        "gapFraction": split.gapFraction, "automaticGap": split.automaticGap,
    ]
    if let halves = split.halves {
        s["halves"] = halves.map { ["left": $0.left.map(dumpKey), "right": $0.right.map(dumpKey)] as Dump }
    }
    return s
}

func dumpLayout(_ layout: LayoutDefinition) -> Dump {
    var d: Dump = ["id": layout.id, "name": layout.name, "rows": layout.rows.map { $0.keys.map(dumpKey) }]
    var input: Dump = [:]
    if let schema = layout.input.schemaID { input["schemaID"] = schema }
    if let builtin = layout.input.builtinLayout { input["builtinLayout"] = builtin.rawValue }
    d["input"] = input
    if let note = layout.note { d["note"] = note }
    if let delay = layout.longPressDelay { d["longPressDelay"] = delay }
    if let behavior = layout.t9FirstKeyBehavior { d["t9FirstKeyBehavior"] = behavior.rawValue }
    if let symbols = layout.t9QuickSymbols { d["t9QuickSymbols"] = symbols }
    if let area = layout.candidateArea { d["candidateArea"] = ["candidates": area.candidates, "reading": area.reading] as Dump }
    if let areas = layout.auxiliaryAreas {
        d["auxiliaryAreas"] = areas.map {
            ["position": $0.position.rawValue, "role": $0.role.rawValue, "keys": $0.keys.map(dumpKey)] as Dump
        }
    }
    if let split = layout.split { d["split"] = dumpSplit(split) }
    return d
}

func dumpPage(_ page: KeyboardPageDefinition) -> Dump {
    var d: Dump = ["id": page.id, "name": page.name, "kind": page.kind.rawValue, "rows": page.rows.map { $0.keys.map(dumpKey) }]
    if let split = page.split { d["split"] = dumpSplit(split) }
    if let value = page.symbolPresentation { d["symbolPresentation"] = value.rawValue }
    if let value = page.numericPresentation { d["numericPresentation"] = value.rawValue }
    if let value = page.defaultSymbolMarks { d["defaultSymbolMarks"] = value.rawValue }
    d["categoryCount"] = page.categories?.count ?? 0
    d["hasChrome"] = page.chrome != nil
    return d
}

func jsonData(_ object: Any) throws -> Data {
    try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys, .prettyPrinted])
}

func tree<T: Encodable>(_ value: T) throws -> Any {
    try JSONSerialization.jsonObject(with: JSONEncoder().encode(value))
}

/// One input file in, `<name>.dump.json` and `<name>.enc.json` out. A rejected file is dumped as an error.
func dump(from dir: URL, to out: URL) throws {
    try FileManager.default.createDirectory(at: out, withIntermediateDirectories: true)
    let names = try FileManager.default.contentsOfDirectory(atPath: dir.path).filter { $0.hasSuffix(".json") }.sorted()
    for name in names {
        let data = try Data(contentsOf: dir.appendingPathComponent(name))
        var result: Dump
        var encoded: Data?
        do {
            if name == "layouts.json" || name.hasPrefix("catalog-") {
                let registry = try JSONDecoder().decode(LayoutRegistry.self, from: data)
                try registry.validate()
                var pages: Dump = [:]
                for page in registry.pages { pages[page.id] = dumpPage(page) }
                var layouts: Dump = [:]
                for layout in registry.layouts { layouts[layout.id] = dumpLayout(layout) }
                result = [
                    "ok": true, "kind": "catalog", "layouts": layouts, "pages": pages,
                    "compositions": try tree(registry.compositions),
                ]
                let scratch = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
                let store = LayoutStore(directoryURL: scratch)
                try store.save(registry)
                encoded = try Data(contentsOf: store.catalogURL)
                try? FileManager.default.removeItem(at: scratch)
            } else {
                let layout = try LayoutDefinition.decode(data)
                // What the import screen does: install into a catalog. It must neither fail nor change the layout.
                var registry = try LayoutRegistry()
                let installed = try registry.install(data, replaceExisting: true)
                result = ["ok": true, "kind": "layout", "layout": dumpLayout(layout)]
                if dumpLayout(installed) as NSDictionary != dumpLayout(layout) as NSDictionary {
                    result["installChanged"] = dumpLayout(installed)
                }
                encoded = try layout.encoded()
            }
        } catch {
            result = ["ok": false, "error": "\(error)"]
        }
        try write(try jsonData(result), out, name + ".dump.json")
        if let encoded { try write(encoded, out, name + ".enc.json") }
    }
}

let arguments = CommandLine.arguments
guard arguments.count >= 3 else {
    print("usage: interop export|verify <dir> | dump <in-dir> <out-dir>")
    exit(2)
}
let directory = URL(fileURLWithPath: arguments[2])
switch arguments[1] {
case "export": try export(to: directory)
case "verify": exit(try verify(in: directory) ? 0 : 1)
case "dump":
    guard arguments.count == 4 else { exit(2) }
    try dump(from: directory, to: URL(fileURLWithPath: arguments[3]))
default: exit(2)
}
