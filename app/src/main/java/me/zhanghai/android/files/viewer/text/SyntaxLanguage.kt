/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.text

import java.util.Locale

enum class SyntaxLanguage(
    val displayName: String,
    val extensions: List<String>,
    val keywords: Set<String> = emptySet(),
    val types: Set<String> = emptySet(),
    val commentLine: String? = null,
    val commentBlockStart: String? = null,
    val commentBlockEnd: String? = null,
    val isXmlHtml: Boolean = false
) {
    PLAIN_TEXT(
        "Texto plano",
        listOf("txt", "log", "text")
    ),
    JAVA(
        "Java",
        listOf("java"),
        keywords = setOf(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
            "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
            "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
            "interface", "long", "native", "new", "package", "private", "protected", "public",
            "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
            "throw", "throws", "transient", "try", "void", "volatile", "while", "true", "false", "null"
        ),
        types = setOf(
            "String", "Object", "Class", "Integer", "Long", "Boolean", "Byte", "Short", "Double",
            "Float", "Character", "List", "ArrayList", "Map", "HashMap", "Set", "HashSet", "Exception",
            "Throwable", "Runnable", "Thread", "Context", "Activity", "View", "Bundle"
        ),
        commentLine = "//",
        commentBlockStart = "/*",
        commentBlockEnd = "*/"
    ),
    KOTLIN(
        "Kotlin",
        listOf("kt", "kts"),
        keywords = setOf(
            "as", "as?", "break", "class", "continue", "do", "else", "false", "for", "fun",
            "if", "in", "!in", "interface", "is", "!is", "null", "object", "package", "return",
            "super", "this", "throw", "true", "try", "typealias", "val", "var", "when", "while",
            "by", "catch", "constructor", "delegate", "dynamic", "field", "file", "finally",
            "get", "import", "init", "param", "property", "receiver", "set", "setparam",
            "where", "actual", "abstract", "annotation", "companion", "const", "crossinline",
            "data", "enum", "expect", "external", "final", "infix", "inline", "inner",
            "internal", "lateinit", "noinline", "open", "operator", "out", "override", "private",
            "protected", "public", "reified", "sealed", "suspend", "tailrec", "vararg"
        ),
        types = setOf(
            "String", "Int", "Long", "Boolean", "Float", "Double", "Byte", "Short", "Char",
            "Unit", "Nothing", "Any", "Array", "List", "MutableList", "Map", "MutableMap",
            "Set", "MutableSet", "Sequence", "Pair", "Triple"
        ),
        commentLine = "//",
        commentBlockStart = "/*",
        commentBlockEnd = "*/"
    ),
    SMALI(
        "Smali",
        listOf("smali"),
        keywords = setOf(
            ".class", ".super", ".source", ".implements", ".field", ".method", ".end",
            ".annotation", ".subannotation", ".enum", ".line", ".registers", ".locals",
            ".param", ".parameter", ".prologue", ".epilogue", ".catch", ".catchall", ".restart",
            ".packed-switch", ".sparse-switch", ".array-data",
            "public", "private", "protected", "static", "final", "synchronized", "bridge",
            "varargs", "native", "abstract", "strictfp", "synthetic", "constructor", "declared-synchronized",
            "move", "move/from16", "move-wide", "move-wide/from16", "move-object", "move-object/from16",
            "move-result", "move-result-wide", "move-result-object", "move-exception",
            "return-void", "return", "return-wide", "return-object",
            "const/4", "const/16", "const", "const/high16", "const-wide/16", "const-wide/32", "const-wide",
            "const-wide/high16", "const-string", "const-string/jumbo", "const-class",
            "monitor-enter", "monitor-exit", "check-cast", "instance-of", "new-instance", "new-array",
            "filled-new-array", "filled-new-array/range", "fill-array-data",
            "throw", "goto", "goto/16", "goto/32", "packed-switch", "sparse-switch",
            "cmpl-float", "cmpg-float", "cmpl-double", "cmpg-double", "cmp-long",
            "if-eq", "if-ne", "if-lt", "if-ge", "if-gt", "if-le", "if-eqz", "if-nez", "if-ltz", "if-gez", "if-gtz", "if-lez",
            "aget", "aget-wide", "aget-object", "aget-boolean", "aget-byte", "aget-char", "aget-short",
            "aput", "aput-wide", "aput-object", "aput-boolean", "aput-byte", "aput-char", "aput-short",
            "iget", "iget-wide", "iget-object", "iget-boolean", "iget-byte", "iget-char", "iget-short",
            "iput", "iput-wide", "iput-object", "iput-boolean", "iput-byte", "iput-char", "iput-short",
            "sget", "sget-wide", "sget-object", "sget-boolean", "sget-byte", "sget-char", "sget-short",
            "sput", "sput-wide", "sput-object", "sput-boolean", "sput-byte", "sput-char", "sput-short",
            "invoke-virtual", "invoke-super", "invoke-direct", "invoke-static", "invoke-interface",
            "invoke-virtual/range", "invoke-super/range", "invoke-direct/range", "invoke-static/range", "invoke-interface/range",
            "invoke-polymorphic", "invoke-custom",
            "neg-int", "not-int", "neg-long", "not-long", "neg-float", "neg-double", "int-to-long", "int-to-float",
            "int-to-double", "long-to-int", "long-to-float", "long-to-double", "float-to-int", "float-to-long",
            "float-to-double", "double-to-int", "double-to-long", "double-to-float", "int-to-byte", "int-to-char", "int-to-short",
            "add-int", "sub-int", "mul-int", "div-int", "rem-int", "and-int", "or-int", "xor-int", "shl-int", "shr-int", "ushr-int"
        ),
        types = setOf("V", "Z", "B", "S", "C", "I", "J", "F", "D", "Ljava/lang/String;", "Ljava/lang/Object;"),
        commentLine = "#"
    ),
    PHP(
        "PHP",
        listOf("php", "phtml", "php3", "php4", "php5", "phps"),
        keywords = setOf(
            "__halt_compiler", "abstract", "and", "array", "as", "break", "callable", "case",
            "catch", "class", "clone", "const", "continue", "declare", "default", "die", "do",
            "echo", "else", "elseif", "empty", "enddeclare", "endfor", "endforeach", "endif",
            "endswitch", "endwhile", "eval", "exit", "extends", "final", "finally", "fn", "for",
            "foreach", "function", "global", "goto", "if", "implements", "include", "include_once",
            "instanceof", "insteadof", "interface", "isset", "list", "match", "namespace", "new",
            "or", "print", "private", "protected", "public", "readonly", "require", "require_once",
            "return", "static", "switch", "throw", "trait", "try", "unset", "use", "var", "while",
            "xor", "yield", "true", "false", "null", "self", "parent"
        ),
        types = setOf("int", "float", "bool", "string", "array", "object", "iterable", "mixed", "void"),
        commentLine = "//",
        commentBlockStart = "/*",
        commentBlockEnd = "*/"
    ),
    PYTHON(
        "Python",
        listOf("py", "pyw", "py3", "pyx", "wsgi"),
        keywords = setOf(
            "False", "None", "True", "and", "as", "assert", "async", "await", "break", "class",
            "continue", "def", "del", "elif", "else", "except", "finally", "for", "from",
            "global", "if", "import", "in", "is", "lambda", "nonlocal", "not", "or", "pass",
            "raise", "return", "try", "while", "with", "yield", "match", "case"
        ),
        types = setOf(
            "int", "float", "str", "bool", "list", "dict", "set", "tuple", "bytes", "object",
            "type", "len", "print", "range", "enumerate", "zip", "isinstance", "super"
        ),
        commentLine = "#"
    ),
    JAVASCRIPT(
        "JavaScript",
        listOf("js", "mjs", "cjs"),
        keywords = setOf(
            "async", "await", "break", "case", "catch", "class", "const", "continue", "debugger",
            "default", "delete", "do", "else", "export", "extends", "finally", "for", "function",
            "if", "import", "in", "instanceof", "let", "new", "return", "super", "switch", "this",
            "throw", "try", "typeof", "var", "void", "while", "with", "yield", "true", "false", "null", "undefined"
        ),
        types = setOf(
            "String", "Number", "Boolean", "Object", "Array", "Function", "Symbol", "BigInt",
            "Promise", "Map", "Set", "WeakMap", "WeakSet", "Date", "RegExp", "Error", "JSON", "Math", "console"
        ),
        commentLine = "//",
        commentBlockStart = "/*",
        commentBlockEnd = "*/"
    ),
    TYPESCRIPT(
        "TypeScript",
        listOf("ts", "tsx", "jsx"),
        keywords = setOf(
            "async", "await", "break", "case", "catch", "class", "const", "continue", "debugger",
            "default", "delete", "do", "else", "export", "extends", "finally", "for", "function",
            "if", "import", "in", "instanceof", "let", "new", "return", "super", "switch", "this",
            "throw", "try", "typeof", "var", "void", "while", "with", "yield", "type", "interface",
            "enum", "implements", "declare", "namespace", "module", "abstract", "readonly",
            "keyof", "unique", "infer", "is", "as", "satisfies", "true", "false", "null", "undefined"
        ),
        types = setOf("string", "number", "boolean", "any", "unknown", "never", "void", "object", "symbol", "bigint"),
        commentLine = "//",
        commentBlockStart = "/*",
        commentBlockEnd = "*/"
    ),
    C_CPP(
        "C / C++",
        listOf("c", "cpp", "cc", "cxx", "h", "hpp"),
        keywords = setOf(
            "auto", "break", "case", "char", "const", "continue", "default", "do", "double",
            "else", "enum", "extern", "float", "for", "goto", "if", "inline", "int", "long",
            "register", "restrict", "return", "short", "signed", "sizeof", "static", "struct",
            "switch", "typedef", "union", "unsigned", "void", "volatile", "while",
            "class", "public", "private", "protected", "virtual", "friend", "template", "typename",
            "this", "new", "delete", "throw", "try", "catch", "namespace", "using", "operator",
            "nullptr", "constexpr", "decltype", "override", "final", "static_assert"
        ),
        types = setOf(
            "size_t", "uint8_t", "uint16_t", "uint32_t", "uint64_t", "int8_t", "int16_t", "int32_t",
            "int64_t", "string", "vector", "map", "set", "shared_ptr", "unique_ptr"
        ),
        commentLine = "//",
        commentBlockStart = "/*",
        commentBlockEnd = "*/"
    ),
    SHELL(
        "Shell / Bash",
        listOf("sh", "bash", "zsh", "rc", "env"),
        keywords = setOf(
            "if", "then", "else", "elif", "fi", "case", "esac", "for", "while", "until", "do",
            "done", "in", "function", "select", "time", "return", "exit", "export", "local",
            "readonly", "set", "unset", "shift", "source", "alias", "echo", "cd", "pwd"
        ),
        commentLine = "#"
    ),
    SQL(
        "SQL",
        listOf("sql"),
        keywords = setOf(
            "SELECT", "FROM", "WHERE", "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE",
            "CREATE", "TABLE", "DROP", "ALTER", "ADD", "COLUMN", "PRIMARY", "KEY", "FOREIGN",
            "REFERENCES", "JOIN", "INNER", "LEFT", "RIGHT", "OUTER", "CROSS", "ON", "GROUP",
            "BY", "ORDER", "ASC", "DESC", "HAVING", "LIMIT", "OFFSET", "UNION", "ALL", "DISTINCT",
            "AS", "AND", "OR", "NOT", "NULL", "IS", "IN", "BETWEEN", "LIKE", "CASE", "WHEN", "THEN",
            "ELSE", "END", "INDEX", "VIEW", "TRIGGER", "DEFAULT", "CHECK", "UNIQUE", "CASCADE"
        ),
        types = setOf("INTEGER", "TEXT", "REAL", "BLOB", "VARCHAR", "CHAR", "BOOLEAN", "DATE", "TIMESTAMP"),
        commentLine = "--",
        commentBlockStart = "/*",
        commentBlockEnd = "*/"
    ),
    XML(
        "XML / AndroidManifest",
        listOf("xml", "axml", "plist", "svg", "xbl", "xsd"),
        isXmlHtml = true,
        commentBlockStart = "<!--",
        commentBlockEnd = "-->"
    ),
    HTML(
        "HTML",
        listOf("html", "htm", "xhtml"),
        isXmlHtml = true,
        commentBlockStart = "<!--",
        commentBlockEnd = "-->"
    ),
    JSON(
        "JSON",
        listOf("json", "json5"),
        keywords = setOf("true", "false", "null")
    ),
    YAML(
        "YAML",
        listOf("yaml", "yml"),
        keywords = setOf("true", "false", "yes", "no", "on", "off", "null"),
        commentLine = "#"
    ),
    TOML(
        "TOML",
        listOf("toml"),
        keywords = setOf("true", "false"),
        commentLine = "#"
    ),
    PROPERTIES(
        "Properties / INI / Conf",
        listOf("properties", "ini", "conf", "cfg", "prop"),
        commentLine = "#"
    ),
    MARKDOWN(
        "Markdown",
        listOf("md", "markdown")
    ),
    RUST(
        "Rust",
        listOf("rs"),
        keywords = setOf(
            "as", "async", "await", "break", "const", "continue", "crate", "dyn", "else", "enum",
            "extern", "false", "fn", "for", "if", "impl", "in", "let", "loop", "match", "mod",
            "move", "mut", "pub", "ref", "return", "self", "Self", "static", "struct", "super",
            "trait", "true", "type", "union", "unsafe", "use", "where", "while"
        ),
        types = setOf("i8", "i16", "i32", "i64", "i128", "u8", "u16", "u32", "u64", "u128", "f32", "f64", "bool", "char", "str", "String", "Vec", "Option", "Result"),
        commentLine = "//",
        commentBlockStart = "/*",
        commentBlockEnd = "*/"
    ),
    GO(
        "Go",
        listOf("go"),
        keywords = setOf(
            "break", "case", "chan", "const", "continue", "default", "defer", "else", "fallthrough",
            "for", "func", "go", "goto", "if", "import", "interface", "map", "package", "range",
            "return", "select", "struct", "switch", "type", "var", "true", "false", "nil", "iota"
        ),
        types = setOf("bool", "string", "int", "int8", "int16", "int32", "int64", "uint", "uint8", "uint16", "uint32", "uint64", "uintptr", "byte", "rune", "float32", "float64", "error"),
        commentLine = "//",
        commentBlockStart = "/*",
        commentBlockEnd = "*/"
    ),
    LUA(
        "Lua",
        listOf("lua"),
        keywords = setOf(
            "and", "break", "do", "else", "elseif", "end", "false", "for", "function", "goto",
            "if", "in", "local", "nil", "not", "or", "repeat", "return", "then", "true", "until", "while"
        ),
        commentLine = "--",
        commentBlockStart = "--[[",
        commentBlockEnd = "]]"
    ),
    CSS(
        "CSS / SCSS",
        listOf("css", "scss", "less"),
        commentBlockStart = "/*",
        commentBlockEnd = "*/"
    ),
    GRADLE(
        "Gradle / Groovy",
        listOf("gradle", "groovy"),
        keywords = setOf(
            "plugins", "android", "defaultConfig", "buildTypes", "dependencies", "implementation",
            "api", "compileOnly", "runtimeOnly", "testImplementation", "androidTestImplementation",
            "apply", "plugin", "repositories", "google", "mavenCentral", "task", "def", "as",
            "import", "package", "class", "interface", "enum", "extends", "implements", "return",
            "if", "else", "while", "for", "in", "switch", "case", "default", "break", "continue",
            "throw", "try", "catch", "finally", "new", "true", "false", "null"
        ),
        types = setOf("String", "Integer", "Long", "Boolean", "File", "Project", "Action", "Closure"),
        commentLine = "//",
        commentBlockStart = "/*",
        commentBlockEnd = "*/"
    ),
    DART(
        "Dart",
        listOf("dart"),
        keywords = setOf(
            "abstract", "as", "assert", "async", "await", "break", "case", "catch", "class",
            "const", "continue", "covariant", "default", "deferred", "do", "dynamic", "else",
            "enum", "export", "extends", "extension", "external", "factory", "false", "final",
            "finally", "for", "Function", "get", "hide", "if", "implements", "import", "in",
            "interface", "is", "late", "library", "mixin", "new", "null", "on", "operator",
            "part", "required", "rethrow", "return", "set", "show", "static", "super", "switch",
            "sync", "this", "throw", "true", "try", "typedef", "var", "void", "while", "with", "yield"
        ),
        types = setOf("int", "double", "num", "String", "bool", "List", "Map", "Set", "Object", "Future", "Stream", "Widget", "State"),
        commentLine = "//",
        commentBlockStart = "/*",
        commentBlockEnd = "*/"
    ),
    CSHARP(
        "C# / .NET",
        listOf("cs"),
        keywords = setOf(
            "abstract", "as", "base", "bool", "break", "byte", "case", "catch", "char", "checked",
            "class", "const", "continue", "decimal", "default", "delegate", "do", "double", "else",
            "enum", "event", "explicit", "extern", "false", "finally", "fixed", "float", "for",
            "foreach", "goto", "if", "implicit", "in", "int", "interface", "internal", "is", "lock",
            "long", "namespace", "new", "null", "object", "operator", "out", "override", "params",
            "private", "protected", "public", "readonly", "ref", "return", "sbyte", "sealed",
            "short", "sizeof", "stackalloc", "static", "string", "struct", "switch", "this",
            "throw", "true", "try", "typeof", "uint", "ulong", "unchecked", "unsafe", "ushort",
            "using", "virtual", "void", "volatile", "while", "yield", "async", "await", "var", "record"
        ),
        types = setOf("String", "Int32", "Int64", "Boolean", "Task", "List", "Dictionary", "IEnumerable", "Action", "Func", "Exception"),
        commentLine = "//",
        commentBlockStart = "/*",
        commentBlockEnd = "*/"
    ),
    RUBY(
        "Ruby",
        listOf("rb", "rake", "gemspec"),
        keywords = setOf(
            "alias", "and", "begin", "break", "case", "class", "def", "defined?", "do", "else",
            "elsif", "end", "ensure", "false", "for", "if", "in", "module", "next", "nil", "not",
            "or", "redo", "rescue", "retry", "return", "self", "super", "then", "true", "undef",
            "unless", "until", "when", "while", "yield", "require", "include", "attr_accessor", "attr_reader"
        ),
        commentLine = "#"
    ),
    SWIFT(
        "Swift",
        listOf("swift"),
        keywords = setOf(
            "associatedtype", "class", "deinit", "enum", "extension", "fileprivate", "func", "import",
            "init", "inout", "internal", "let", "open", "operator", "private", "protocol", "public",
            "rethrows", "static", "struct", "subscript", "typealias", "var", "break", "case", "continue",
            "default", "defer", "do", "else", "fallthrough", "for", "guard", "if", "in", "repeat",
            "return", "switch", "where", "while", "as", "Any", "catch", "false", "is", "nil",
            "super", "self", "Self", "throw", "throws", "true", "try", "async", "await", "actor"
        ),
        types = setOf("Int", "Double", "Float", "String", "Bool", "Array", "Dictionary", "Set", "Optional", "Character", "View"),
        commentLine = "//",
        commentBlockStart = "/*",
        commentBlockEnd = "*/"
    ),
    BATCH(
        "Batch / CMD",
        listOf("bat", "cmd"),
        keywords = setOf(
            "echo", "set", "if", "else", "goto", "call", "exit", "pause", "rem", "for", "in",
            "do", "shift", "cls", "title", "color", "start", "type", "copy", "del", "md", "rd", "ren"
        ),
        commentLine = "rem"
    ),
    CMAKE(
        "CMake",
        listOf("cmake"),
        keywords = setOf(
            "cmake_minimum_required", "project", "set", "add_executable", "add_library",
            "target_link_libraries", "target_include_directories", "find_package", "include",
            "if", "endif", "else", "elseif", "foreach", "endforeach", "while", "endwhile",
            "function", "endfunction", "macro", "endmacro", "message", "option", "install"
        ),
        commentLine = "#"
    );

    companion object {
        fun detectFromFileName(fileName: String): SyntaxLanguage {
            val lower = fileName.lowercase(Locale.ROOT)
            val ext = lower.substringAfterLast('.', "")
            if (ext.isNotEmpty()) {
                for (lang in entries) {
                    if (ext in lang.extensions) {
                        return lang
                    }
                }
            }
            return when {
                lower == "build.gradle" || lower == "settings.gradle" -> GRADLE
                lower == "cmakelists.txt" -> CMAKE
                lower.startsWith("readme") || lower.startsWith("changelog") -> MARKDOWN
                lower == "dockerfile" || lower == "makefile" -> SHELL
                lower == "gemfile" || lower == "rakefile" -> RUBY
                lower == "hosts" || lower.endsWith(".conf") || lower.endsWith(".prop") || lower == ".env" || lower == ".gitignore" || lower == ".gitconfig" -> PROPERTIES
                else -> PLAIN_TEXT
            }
        }
    }
}
