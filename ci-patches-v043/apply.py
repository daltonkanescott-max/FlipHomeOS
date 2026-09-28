from pathlib import Path
import sys

root = Path(sys.argv[1])

# v0.4.2 has already been applied by the workflow.

main = root / "app/src/main/java/com/fliphomeos/app/ui/MainActivity.kt"
s = main.read_text()

old_click = """        binding.editBadge.setOnClickListener {
            setEditMode(!editMode)
        }"""
new_click = """        binding.editBadge.setOnClickListener {
            if (editMode) {
                setEditMode(false)
            } else {
                showEditMenu()
            }
        }"""
if old_click not in s:
    raise SystemExit("EDIT click patch target not found")
s = s.replace(old_click, new_click, 1)

old_mode = """    private fun setEditMode(enabled: Boolean) {
        editMode = enabled
        binding.editPanel.visibility = if (enabled) View.VISIBLE else View.GONE
        binding.editBadge.visibility = View.VISIBLE
        binding.editBadge.text = if (enabled) "EDITING" else "EDIT"
        pagerAdapter.setEditing(enabled)
    }"""

new_mode = """    private fun showEditMenu() {
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        var dialog: android.app.AlertDialog? = null

        fun menuButton(
            label: String,
            action: () -> Unit
        ): android.widget.Button {
            return android.widget.Button(this).apply {
                text = label
                textSize = 14f
                minHeight = dp(52)
                setAllCaps(false)
                setOnClickListener {
                    dialog?.dismiss()
                    action()
                }
            }
        }

        fun row(vararg buttons: android.widget.Button): android.widget.LinearLayout {
            return android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER
                buttons.forEach { button ->
                    addView(
                        button,
                        android.widget.LinearLayout.LayoutParams(
                            0,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                            1f
                        ).apply {
                            setMargins(dp(3), dp(3), dp(3), dp(3))
                        }
                    )
                }
            }
        }

        val content = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(8))

            addView(
                row(
                    menuButton("Apps") { binding.appsButton.performClick() },
                    menuButton("Folder") { binding.folderButton.performClick() },
                    menuButton("Page") { binding.pageButton.performClick() }
                )
            )

            addView(
                row(
                    menuButton("Style") { binding.styleButton.performClick() },
                    menuButton("Wallpaper") { binding.wallpaperButton.performClick() },
                    menuButton("Arrange") { setEditMode(true) }
                )
            )
        }

        dialog = android.app.AlertDialog.Builder(this)
            .setTitle("Edit Home")
            .setView(content)
            .setNegativeButton("Close", null)
            .create()

        dialog?.show()
    }

    private fun setEditMode(enabled: Boolean) {
        editMode = enabled
        binding.editPanel.visibility = View.GONE
        binding.editBadge.visibility = View.VISIBLE
        binding.editBadge.text = if (enabled) "DONE" else "EDIT"
        pagerAdapter.setEditing(enabled)
    }"""

if old_mode not in s:
    raise SystemExit("Edit mode patch target not found")
s = s.replace(old_mode, new_mode, 1)

# Make the EDIT badge itself a larger touch target at runtime.
needle = """        binding.editBadge.visibility = View.VISIBLE
        binding.editBadge.text = "EDIT"
        binding.editBadge.setOnClickListener {"""
replacement = """        binding.editBadge.visibility = View.VISIBLE
        binding.editBadge.text = "EDIT"
        binding.editBadge.minWidth = (52 * resources.displayMetrics.density).toInt()
        binding.editBadge.minHeight = (34 * resources.displayMetrics.density).toInt()
        binding.editBadge.setPadding(
            (8 * resources.displayMetrics.density).toInt(),
            0,
            (8 * resources.displayMetrics.density).toInt(),
            0
        )
        binding.editBadge.setBackgroundResource(com.fliphomeos.app.R.drawable.edit_panel_background)
        binding.editBadge.setOnClickListener {"""
if needle not in s:
    raise SystemExit("EDIT sizing patch target not found")
s = s.replace(needle, replacement, 1)

main.write_text(s)

gradle = root / "app/build.gradle"
g = gradle.read_text()
g = g.replace("versionCode 8", "versionCode 9", 1)
g = g.replace("versionName '0.4.2-experimental'", "versionName '0.4.3-experimental'", 1)
gradle.write_text(g)

print("v0.4.3 cover usability patch applied")
