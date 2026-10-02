# GridLoad recorder 2
# Records the meter's quarter hours (:00, :15, :30, :45) on the SD card for the GridLoad app,
# https://github.com/buerlino/gridload (GPLv3). Installed and updated by the app.
# One file per local day, /sdcard/GLyymmdd.CSV, one line per quarter: its start (UTC, epoch
# seconds), the kWh drawn in it and the import register at its end. The register at each
# boundary is interpolated between the readings either side, by the meter's clock. A line
# "<time>,start" marks each start of the script, at its first reading.
import ww
import string
import time

gl_t = nil   # the last reading: meter time and register
gl_e = nil
gl_qt = nil  # the current quarter's start and the register there, once a boundary was seen
gl_qe = nil

def gl_write(t, line)
  var d = time.dump(t)
  var name = string.format('/sdcard/GL%02d%02d%02d.CSV', d['year'] % 100, d['month'], d['day'])
  try
    var f = open(name, 'a')
    f.write(line + '\n')
    f.close()
  except ..
  end
end

def gl_report(r)
  var t = r.find('timestamp')
  var e = r.find('energy', {}).find('active', {}).find('positive', {}).find('total')
  if t == nil || e == nil || e <= 0 return end
  if gl_t == nil gl_write(t, string.format('%d,start', t)) end
  if gl_t != nil && t <= gl_t return end
  var pt = gl_t
  var pe = gl_e
  gl_t = t
  gl_e = e
  if pt == nil return end
  if e < pe gl_qt = nil return end
  var b = (pt / 900 + 1) * 900
  if b > t return end
  if t - pt > 30 gl_qt = nil return end
  var eb = pe + (e - pe) * real(b - pt) / real(t - pt)
  if gl_qt != nil && gl_qt == b - 900
    gl_write(gl_qt, string.format('%d,%.4f,%.4f', gl_qt, eb - gl_qe, eb))
  end
  gl_qt = b
  gl_qe = eb
end

ww.onreport('gl_report')
