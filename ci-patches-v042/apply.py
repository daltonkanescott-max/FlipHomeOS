from pathlib import Path
import sys

root = Path(sys.argv[1])

main = root / "app/src/main/java/com/fliphomeos/app/ui/MainActivity.kt"
s = main.read_text()

replacements = [
(
"""        binding.drawerButton.setOnClickListener {
            runCatching {
                CoverDisplayLauncher.launch(this, Intent(this, AppDrawerActivity::class.java))
            }
        }

        binding.appsButton.setOnClickListener {""",
"""        binding.drawerButton.setOnClickListener {
            runCatching {
                CoverDisplayLauncher.launch(this, Intent(this, AppDrawerActivity::class.java))
            }
        }

        binding.editBadge.visibility = View.VISIBLE
        binding.editBadge.text = "EDIT"
        binding.editBadge.setOnClickListener {
            setEditMode(!editMode)
        }

        binding.appsButton.setOnClickListener {"""
),
(
"""    private fun setEditMode(enabled: Boolean) {
        editMode = enabled
        binding.editPanel.visibility = if (enabled) View.VISIBLE else View.GONE
        binding.editBadge.visibility = if (enabled) View.VISIBLE else View.GONE
        pagerAdapter.setEditing(enabled)
    }""",
"""    private fun setEditMode(enabled: Boolean) {
        editMode = enabled
        binding.editPanel.visibility = if (enabled) View.VISIBLE else View.GONE
        binding.editBadge.visibility = View.VISIBLE
        binding.editBadge.text = if (enabled) "EDITING" else "EDIT"
        pagerAdapter.setEditing(enabled)
    }"""
),
(
"""    private fun updatePageIndicator() {
        if (pages.size <= 1) {
            binding.pageIndicator.visibility = View.INVISIBLE
            return
        }
        binding.pageIndicator.visibility = View.VISIBLE
        binding.pageIndicator.text = buildString {
            pages.indices.forEach { index ->
                if (index > 0) append(' ')
                append(if (index == currentPageIndex) '●' else '○')
            }
        }
    }""",
"""    private fun updatePageIndicator() {
        binding.pageIndicator.visibility = View.VISIBLE
        binding.pageIndicator.text = if (pages.size <= 1) {
            "1 / 1"
        } else {
            buildString {
                pages.indices.forEach { index ->
                    if (index > 0) append(' ')
                    append(if (index == currentPageIndex) '●' else '○')
                }
            }
        }
    }"""
)
]

for old, new in replacements:
    if old not in s:
        raise SystemExit("MainActivity patch target not found")
    s = s.replace(old, new, 1)

main.write_text(s)

layout = root / "app/src/main/res/layout/activity_main.xml"
x = layout.read_text()

layout_replacements = [
(
"""            <TextView
                android:id="@+id/drawerButton"
                android:layout_width="40dp"
                android:layout_height="36dp"
                android:gravity="center"
                android:text="@string/all"
                android:textColor="@color/white"
                android:textSize="10sp"
                android:textStyle="bold" />""",
"""            <TextView
                android:id="@+id/drawerButton"
                android:layout_width="wrap_content"
                android:layout_height="32dp"
                android:layout_marginEnd="5dp"
                android:background="@drawable/edit_panel_background"
                android:gravity="center"
                android:minWidth="42dp"
                android:paddingStart="7dp"
                android:paddingEnd="7dp"
                android:text="@string/all"
                android:textColor="@color/white"
                android:textSize="10sp"
                android:textStyle="bold" />"""
),
(
"""                android:text="@string/edit_mode"
                android:textColor="@color/white"
                android:textSize="9sp"
                android:textStyle="bold"
                android:visibility="gone" />""",
"""                android:text="EDIT"
                android:textColor="@color/white"
                android:textSize="9sp"
                android:textStyle="bold"
                android:visibility="visible" />"""
),
(
"""            android:textColor="#CCFFFFFF"
            android:textSize="9sp"
            android:visibility="invisible" />""",
"""            android:textColor="#CCFFFFFF"
            android:textSize="9sp"
            android:visibility="visible" />"""
)
]

for old, new in layout_replacements:
    if old not in x:
        raise SystemExit("activity_main.xml patch target not found")
    x = x.replace(old, new, 1)

layout.write_text(x)

gradle = root / "app/build.gradle"
g = gradle.read_text()
g = g.replace("versionCode 7", "versionCode 8", 1)
g = g.replace("versionName '0.4.0-experimental'", "versionName '0.4.2-experimental'", 1)
gradle.write_text(g)

print("v0.4.2 visible EDIT controls applied")
