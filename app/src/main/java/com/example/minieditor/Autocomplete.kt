package com.example.minieditor

/** Suggestions for the Lua script editor: what could be typed at the cursor right now. */
object Autocomplete {
    class Item(val key: String, val label: String, val insert: String, val back: Int = 0)

    /** The text from [start] up to the cursor is replaced by an item's insert text. */
    class Result(val start: Int, val items: List<Item>)

    private fun plain(k: String) = Item(k, k, k)
    private fun call(k: String, args: String = "", back: Int = 1) = Item(k, "$k(", "$k($args)", back)
    private fun snip(key: String, label: String, head: String, tail: String) = Item(key, label, head + tail, tail.length)

    private val keywords = listOf(
        snip("function", "function", "function ", "()\n  \nend"),
        snip("update", "function update(dt)", "function update(dt)\n  ", "\nend"),
        snip("start", "function start()", "function start()\n  ", "\nend"),
        snip("oncollide", "function oncollide(other)", "function oncollide(other)\n  ", "\nend"),
        snip("if", "if ... then", "if ", " then\n  \nend"),
        snip("for", "for i = 1, n do", "for i = 1, ", " do\n  \nend"),
        snip("while", "while ... do", "while ", " do\n  \nend"),
        Item("local", "local", "local ", 0),
        Item("return", "return", "return ", 0),
        plain("then"), plain("else"), plain("elseif"), plain("end"), plain("do"), plain("break"),
        plain("true"), plain("false"), plain("nil"), plain("and"), plain("or"), plain("not"), plain("in")
    )

    private val globals = listOf(
        Item("self", "self.", "self.", 0),
        Item("input", "input.", "input.", 0),
        Item("world", "world.", "world.", 0),
        Item("camera", "camera.", "camera.", 0),
        Item("math", "math.", "math.", 0),
        Item("string", "string.", "string.", 0),
        Item("table", "table.", "table.", 0),
        Item("find", "find(\"\")", "find(\"\")", 2),
        Item("spawn", "spawn(\"cube\", x, y, z)", "spawn(\"cube\", 0, 0, 0)", 0),
        call("destroy", "", 1),
        Item("groundHeight", "groundHeight(x, z)", "groundHeight(self.x, self.z)", 0),
        Item("waterHeight", "waterHeight(x, z)", "waterHeight(self.x, self.z)", 0),
        Item("log", "log(\"\")", "log(\"\")", 2),
        Item("print", "print(\"\")", "print(\"\")", 2),
        Item("playSound", "playSound(\"\")", "playSound(\"\")", 2),
        Item("playMusic", "playMusic(\"\")", "playMusic(\"\")", 2),
        Item("stopMusic", "stopMusic()", "stopMusic()", 0),
        Item("setMusicVolume", "setMusicVolume(v)", "setMusicVolume(1)", 0),
        plain("time"), plain("dt"),
        Item("ipairs", "ipairs()", "ipairs()", 1), Item("pairs", "pairs()", "pairs()", 1),
        Item("tostring", "tostring()", "tostring()", 1), Item("tonumber", "tonumber()", "tonumber()", 1),
        Item("type", "type()", "type()", 1)
    )

    private val selfFields = listOf(
        "name", "x", "y", "z", "rx", "ry", "rz", "sx", "sy", "sz", "r", "g", "b",
        "collider", "opacity", "emit", "grounded", "inWater", "waterY"
    )
    private val inputFields = listOf(
        "touching", "x", "y", "tiltX", "tiltY", "moveX", "moveZ", "joyX", "joyY", "joyMoveX", "joyMoveZ",
        "joyActive", "joyTap", "jump", "jumpPressed", "buttons", "buttonsPressed", "yaw", "ax", "ay", "az"
    )
    private val worldFields = listOf("gravity", "fog", "ambient", "timeScale", "wind", "windDir")
    private val cameraFields = listOf("firstPerson", "target", "yaw", "pitch")
    private val mathFns = listOf("abs", "ceil", "cos", "deg", "exp", "floor", "fmod", "log", "max", "min", "rad", "random", "randomseed", "sin", "sqrt", "tan", "atan")
    private val stringFns = listOf("format", "sub", "len", "upper", "lower", "find", "rep")
    private val tableFns = listOf("insert", "remove", "sort", "concat")

    private fun fields(list: List<String>) = list.map { plain(it) }
    private fun fns(list: List<String>) = list.map { Item(it, "$it(", "$it()", 1) }

    private val keywordSet = setOf("function", "local", "return", "then", "else", "elseif", "end", "do", "break", "true", "false", "nil",
        "and", "or", "not", "in", "if", "for", "while", "repeat", "until")

    private val objVarRe = Regex("""local\s+([A-Za-z_]\w*)\s*=\s*(?:find|spawn)\s*\(""")
    private val stringCtxRe = Regex("""(find|spawn|playSound|playMusic)\s*\(\s*"([^"]*)$""")
    private val buttonCtxRe = Regex("""buttons(?:Pressed)?\s*\[\s*"([^"]*)$""")
    private val memberRe = Regex("""([A-Za-z_]\w*)\.(\w*)$""")
    private val wordRe = Regex("""([A-Za-z_]\w*)$""")
    private val identRe = Regex("""[A-Za-z_]\w{2,}""")

    fun compute(
        text: String, cursorPos: Int,
        objectNames: () -> List<String>, buttonNames: () -> List<String>, soundNames: () -> List<String>
    ): Result? {
        val cursor = cursorPos.coerceIn(0, text.length)
        val lineStart = text.lastIndexOf('\n', cursor - 1) + 1
        val before = text.substring(lineStart, cursor)
        val closeQuote = text.getOrNull(cursor) != '"'

        // names inside quotes: find("..."), playSound("..."), input.buttons["..."]
        val sm = stringCtxRe.find(before)
        val bm = if (sm == null) buttonCtxRe.find(before) else null
        if (sm != null || bm != null) {
            val prefix = if (sm != null) sm.groupValues[2] else bm!!.groupValues[1]
            val pool = if (bm != null) buttonNames() else when (sm!!.groupValues[1]) {
                "find" -> objectNames()
                "spawn" -> listOf("cube", "pyramid", "sphere")
                else -> soundNames()
            }
            val items = pool.distinct().filter { !it.contains('"') && !it.contains('\\') && it.startsWith(prefix, true) && it != prefix }
                .take(20).map { Item(it, it, it + (if (closeQuote) "\"" else ""), 0) }
            return if (items.isEmpty()) null else Result(cursor - prefix.length, items)
        }
        if (before.count { it == '"' } % 2 == 1) return null   // inside some other string
        val cm = before.indexOf("--")
        if (cm >= 0) return null                                // inside a comment

        // members: self.  input.  math.  or a variable from  local e = find("...")
        val mm = memberRe.find(before)
        if (mm != null) {
            val obj = mm.groupValues[1]
            val prefix = mm.groupValues[2]
            val vars = objVarRe.findAll(text).map { it.groupValues[1] }.toSet() + "other"
            val all = when {
                obj == "self" || obj in vars -> fields(selfFields)
                obj == "input" -> fields(inputFields)
                obj == "world" -> fields(worldFields)
                obj == "camera" -> fields(cameraFields)
                obj == "math" -> fns(mathFns) + plain("pi") + plain("huge")
                obj == "string" -> fns(stringFns)
                obj == "table" -> fns(tableFns)
                else -> return null
            }
            val items = all.filter { it.key.startsWith(prefix, true) && it.key != prefix }.take(30)
            return if (items.isEmpty()) null else Result(cursor - prefix.length, items)
        }

        // plain words: keywords, the app's functions, then other words used in this script
        val wm = wordRe.find(before) ?: return null
        val prefix = wm.groupValues[1]
        val start = cursor - prefix.length
        val out = ArrayList<Item>()
        val seen = HashSet<String>()
        for (g in globals + keywords) {
            if (g.key.startsWith(prefix, true) && g.key != prefix && seen.add(g.key)) out.add(g)
        }
        val rest = text.substring(0, start) + " " + text.substring(cursor)
        for (m in identRe.findAll(rest)) {
            val w = m.value
            if (w in keywordSet || !w.startsWith(prefix, true) || w == prefix) continue
            if (seen.add(w)) out.add(plain(w))
        }
        val items = out.take(14)
        return if (items.isEmpty()) null else Result(start, items)
    }
}
