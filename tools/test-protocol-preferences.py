"""Host test of real AndroidX preference initialization; not Android UI/device testing."""
import argparse
import base64
import json
from pathlib import Path
import subprocess


TEXT_UTILS = '''package android.text;
public final class TextUtils {
  public static boolean isEmpty(CharSequence value) { return value == null || value.length() == 0; }
  public static boolean equals(CharSequence left, CharSequence right) {
    return left == right || left != null && right != null && left.toString().contentEquals(right);
  }
}'''

PROBE = r'''import androidx.preference.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
public class ProtocolPreferenceProbe {
  public static class EditProbe extends EditTextPreference {
    public EditProbe() { super(null); }
    public void attach(PreferenceManager manager) { super.onAttachedToHierarchy(manager); }
    @Override public boolean shouldDisableDependents() { return false; }
    @Override protected void notifyChanged() { }
    @Override public void notifyDependencyChange(boolean disabled) { }
  }
  public static class ListProbe extends ListPreference {
    public ListProbe() { super(null); }
    public void attach(PreferenceManager manager) { super.onAttachedToHierarchy(manager); }
    @Override public boolean shouldDisableDependents() { return false; }
    @Override protected void notifyChanged() { }
    @Override public void notifyDependencyChange(boolean disabled) { }
  }
  public static class Store extends PreferenceDataStore {
    int writes;
    @Override public String getString(String key, String fallback) { return "stale-per-field-cache"; }
    @Override public void putString(String key, String value) { writes++; }
  }
  static <T> T allocate(Class<T> type) throws Exception {
    Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
    Field field = unsafeClass.getDeclaredField("theUnsafe"); field.setAccessible(true);
    return type.cast(unsafeClass.getMethod("allocateInstance", Class.class).invoke(field.get(null), type));
  }
  static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
  static String get(Preference p) { return p instanceof EditTextPreference ? ((EditTextPreference)p).getText() : ((ListPreference)p).getValue(); }
  static void set(Preference p, String value) {
    if (p instanceof EditTextPreference) ((EditTextPreference)p).setText(value); else ((ListPreference)p).setValue(value);
  }
  static void attach(Preference p, PreferenceManager manager) {
    if (p instanceof EditProbe) ((EditProbe)p).attach(manager); else ((ListProbe)p).attach(manager);
  }
  static Preference create(boolean choice) throws Exception {
    Preference p = choice ? allocate(ListProbe.class) : allocate(EditProbe.class);
    p.setKey("protocolAdvanced.synthetic"); p.setPersistent(false);
    return p;
  }
  public static void main(String[] args) throws Exception {
    Store store = new Store();
    PreferenceManager manager = allocate(PreferenceManager.class); manager.setPreferenceDataStore(store);
    for (boolean choice : new boolean[]{false,true}) {
      Preference old = create(choice); set(old,choice ? "false" : "30s"); attach(old,manager);
      require(get(old)==null,"Negative control failed to reproduce cleared value");
    }
    Method bind = Class.forName("io.nekohasekai.sagernet.ui.profile.ProtocolAdvancedPreferencesKt")
        .getDeclaredMethod("bindProtocolValue", Preference.class, String.class);
    int count=0;
    for (String row : Files.readAllLines(Paths.get(args[0]),StandardCharsets.UTF_8)) {
      String[] parts=row.split("\t",-1);
      boolean choice=parts[0].equals("CHOICE")||parts[0].equals("BOOLEAN");
      String value=new String(Base64.getDecoder().decode(parts[1]),StandardCharsets.UTF_8);
      for (String expected : new String[]{value,""}) for (int reopen=0;reopen<2;reopen++) {
        Preference p=create(choice);
        bind.invoke(null,p,expected);
        attach(p,manager);
        require(Objects.equals(expected,get(p)),"Hierarchy initialization lost saved value: "+parts[2]);
        require(!p.isPersistent(),"Per-field persistence unexpectedly enabled");
        count++;
      }
    }
    require(count>0,"No field matrix supplied");
    require(store.writes==0,"Rendering wrote to preference storage");
    System.out.println("PASS: "+count+" real AndroidX hierarchy initialization checks; 2 negative controls reproduced; 0 cache writes");
  }
}'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for argument in ["java-home", "preference-jar", "android-jar", "kotlin-stdlib", "app-classes", "matrix", "output"]:
        parser.add_argument("--" + argument, type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    rows = json.loads(args.matrix.read_text(encoding="utf-8"))
    assert rows and len({x["protocol"].split("-")[0] for x in rows}) >= 10
    matrix = args.output / "matrix.tsv"
    matrix.write_text("\n".join(x["kind"] + "\t" + base64.b64encode(x["value"].encode()).decode() + "\t" + x["protocol"] + ":" + x["path"] for x in rows), encoding="utf-8")
    source_dir = args.output / "source"
    (source_dir / "android/text").mkdir(parents=True)
    (source_dir / "android/text/TextUtils.java").write_text(TEXT_UTILS, encoding="utf-8")
    (source_dir / "ProtocolPreferenceProbe.java").write_text(PROBE, encoding="utf-8")
    classes = args.output / "classes"
    classes.mkdir()
    suffix = ".exe" if (args.java_home / "bin/java.exe").exists() else ""
    separator = ";" if suffix else ":"
    classpath = separator.join(map(str, [classes, args.preference_jar, args.app_classes, args.kotlin_stdlib, args.android_jar]))

    def run(command, log):
        result = subprocess.run(list(map(str, command)), stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        (args.output / log).write_bytes(result.stdout)
        assert result.returncode == 0, f"Host preference test failed: {log}"
        return result.stdout.decode("utf-8", errors="replace")

    run([args.java_home / ("bin/javac" + suffix), "--release", "8", "-cp", classpath, "-d", classes,
         source_dir / "android/text/TextUtils.java", source_dir / "ProtocolPreferenceProbe.java"], "compile.txt")
    result = run([args.java_home / ("bin/java" + suffix), "-cp", classpath, "ProtocolPreferenceProbe", matrix], "run.txt")
    summary = {"matrix_fields": len(rows), "hierarchy_checks": len(rows) * 4, "negative_controls": 2,
               "result": "PASS", "scope": "Actual AndroidX attach/initial-value methods and production binder; constructors, resources and view notifications stubbed; not an Android device test"}
    (args.output / "results.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    print(result.strip())


if __name__ == "__main__":
    main()
