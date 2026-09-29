from pathlib import Path
import sys

p = Path(sys.argv[1])
s = p.read_text()

import_needle = "import com.fliphomeos.app.model.AppEntry\n"
import_line = import_needle + "import com.fliphomeos.app.service.CoverSetupHelper\n"
if "import com.fliphomeos.app.service.CoverSetupHelper" not in s:
    if import_needle not in s:
        raise SystemExit("MainActivity import insertion point not found")
    s = s.replace(import_needle, import_line, 1)

old = """    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(clockRunnable)
        handler.post(clockRunnable)
        loadHomeApps()
    }"""
new = """    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(clockRunnable)
        handler.post(clockRunnable)
        loadHomeApps()
        CoverSetupHelper.ensureReady(this)
    }"""
if "CoverSetupHelper.ensureReady(this)" not in s:
    if old not in s:
        raise SystemExit("MainActivity onResume insertion point not found")
    s = s.replace(old, new, 1)

p.write_text(s)
