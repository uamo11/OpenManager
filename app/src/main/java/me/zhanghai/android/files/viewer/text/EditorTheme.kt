/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.text

enum class EditorTheme(
    val id: String,
    val displayName: String,
    val backgroundColor: Int,
    val gutterColor: Int,
    val gutterTextColor: Int,
    val gutterCurrentLineColor: Int,
    val gutterDividerColor: Int,
    val currentLineColor: Int,
    val textColor: Int,
    val keywordColor: Int,
    val typeColor: Int,
    val stringColor: Int,
    val numberColor: Int,
    val commentColor: Int,
    val annotationColor: Int,
    val tagColor: Int,
    val attributeColor: Int,
    val operatorColor: Int
) {
    ONE_DARK(
        "one_dark",
        "One Dark Pro (MT Dark)",
        backgroundColor = 0xFF1E1E1E.toInt(),
        gutterColor = 0xFF21252B.toInt(),
        gutterTextColor = 0xFF5C6370.toInt(),
        gutterCurrentLineColor = 0xFF61AFEF.toInt(),
        gutterDividerColor = 0xFF3E4451.toInt(),
        currentLineColor = 0x1A61AFEF.toInt(),
        textColor = 0xFFDCDFE4.toInt(),
        keywordColor = 0xFFC678DD.toInt(),
        typeColor = 0xFFE5C07B.toInt(),
        stringColor = 0xFF98C379.toInt(),
        numberColor = 0xFFD19A66.toInt(),
        commentColor = 0xFF7F848E.toInt(),
        annotationColor = 0xFF61AFEF.toInt(),
        tagColor = 0xFFE06C75.toInt(),
        attributeColor = 0xFFD19A66.toInt(),
        operatorColor = 0xFF56B6C2.toInt()
    ),
    DRACULA(
        "dracula",
        "Dracula",
        backgroundColor = 0xFF282A36.toInt(),
        gutterColor = 0xFF21222C.toInt(),
        gutterTextColor = 0xFF6272A4.toInt(),
        gutterCurrentLineColor = 0xFFBD93F9.toInt(),
        gutterDividerColor = 0xFF44475A.toInt(),
        currentLineColor = 0x2244475A.toInt(),
        textColor = 0xFFF8F8F2.toInt(),
        keywordColor = 0xFFFF79C6.toInt(),
        typeColor = 0xFF8BE9FD.toInt(),
        stringColor = 0xFFF1FA8C.toInt(),
        numberColor = 0xFFBD93F9.toInt(),
        commentColor = 0xFF6272A4.toInt(),
        annotationColor = 0xFFFFB86C.toInt(),
        tagColor = 0xFFFF79C6.toInt(),
        attributeColor = 0xFF50FA7B.toInt(),
        operatorColor = 0xFFFF79C6.toInt()
    ),
    MONOKAI(
        "monokai",
        "Monokai",
        backgroundColor = 0xFF272822.toInt(),
        gutterColor = 0xFF1E1F1C.toInt(),
        gutterTextColor = 0xFF75715E.toInt(),
        gutterCurrentLineColor = 0xFFA6E22E.toInt(),
        gutterDividerColor = 0xFF3E3D32.toInt(),
        currentLineColor = 0x223E3D32.toInt(),
        textColor = 0xFFF8F8F2.toInt(),
        keywordColor = 0xFFF92672.toInt(),
        typeColor = 0xFF66D9EF.toInt(),
        stringColor = 0xFFE6DB74.toInt(),
        numberColor = 0xFFAE81FF.toInt(),
        commentColor = 0xFF75715E.toInt(),
        annotationColor = 0xFFA6E22E.toInt(),
        tagColor = 0xFFF92672.toInt(),
        attributeColor = 0xFFA6E22E.toInt(),
        operatorColor = 0xFFF92672.toInt()
    ),
    GITHUB_DARK(
        "github_dark",
        "GitHub Dark",
        backgroundColor = 0xFF0D1117.toInt(),
        gutterColor = 0xFF161B22.toInt(),
        gutterTextColor = 0xFF6E7681.toInt(),
        gutterCurrentLineColor = 0xFF58A6FF.toInt(),
        gutterDividerColor = 0xFF30363D.toInt(),
        currentLineColor = 0x1A58A6FF.toInt(),
        textColor = 0xFFC9D1D9.toInt(),
        keywordColor = 0xFFFF7B72.toInt(),
        typeColor = 0xFFFFA657.toInt(),
        stringColor = 0xFFA5D6FF.toInt(),
        numberColor = 0xFF79C0FF.toInt(),
        commentColor = 0xFF8B949E.toInt(),
        annotationColor = 0xFFD2A8FF.toInt(),
        tagColor = 0xFF7EE787.toInt(),
        attributeColor = 0xFF79C0FF.toInt(),
        operatorColor = 0xFFFF7B72.toInt()
    ),
    LIGHT(
        "light",
        "Light / Solar",
        backgroundColor = 0xFFFBFBFB.toInt(),
        gutterColor = 0xFFF0F0F0.toInt(),
        gutterTextColor = 0xFFA0A0A0.toInt(),
        gutterCurrentLineColor = 0xFF0366D6.toInt(),
        gutterDividerColor = 0xFFE1E4E8.toInt(),
        currentLineColor = 0x150366D6.toInt(),
        textColor = 0xFF24292E.toInt(),
        keywordColor = 0xFFD73A49.toInt(),
        typeColor = 0xFF6F42C1.toInt(),
        stringColor = 0xFF032F62.toInt(),
        numberColor = 0xFF005CC5.toInt(),
        commentColor = 0xFF6A737D.toInt(),
        annotationColor = 0xFFE36209.toInt(),
        tagColor = 0xFF22863A.toInt(),
        attributeColor = 0xFF6F42C1.toInt(),
        operatorColor = 0xFFD73A49.toInt()
    );

    companion object {
        fun fromId(id: String): EditorTheme =
            entries.find { it.id.equals(id, ignoreCase = true) } ?: ONE_DARK
    }
}
