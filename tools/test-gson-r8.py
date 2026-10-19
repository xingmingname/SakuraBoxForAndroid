"""Run the production protocol TypeToken through R8 and execute its Gson conversion."""
import argparse
import json
from pathlib import Path
import subprocess
import zipfile


TOKEN = "io.nekohasekai.sagernet.fmt.ProtocolOptions$applyTo$values$1"
PROBE = r'''import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import java.util.*;
public class GsonReleaseProbe {
  public static void main(String[] args) throws Exception {
    java.lang.reflect.Constructor<?> constructor = Class.forName("TOKEN_CLASS").getDeclaredConstructor();
    constructor.setAccessible(true);
    TypeToken<?> token = (TypeToken<?>)constructor.newInstance();
    if (!(token.getType() instanceof java.lang.reflect.ParameterizedType)) throw new AssertionError("raw map");
    Gson gson = new GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create();
    Map<String,Object> map = gson.fromJson("{\"min_idle_session\":3,\"enabled\":true,\"tls\":{\"server_name\":\"example.invalid\"},\"list\":[1,2.5,null]}", token.getType());
    if (!Long.valueOf(3).equals(map.get("min_idle_session"))) throw new AssertionError("integer semantics");
    if (!Boolean.TRUE.equals(map.get("enabled"))) throw new AssertionError("boolean semantics");
    if (!"example.invalid".equals(((Map<?,?>)map.get("tls")).get("server_name"))) throw new AssertionError("nested map");
    List<?> list = (List<?>)map.get("list");
    if (!Long.valueOf(1).equals(list.get(0)) || !Double.valueOf(2.5).equals(list.get(1)) || list.get(2)!=null) throw new AssertionError("list semantics");
    Map<?,?> empty = gson.fromJson("{}", token.getType());
    if (!empty.isEmpty()) throw new AssertionError("empty legacy options");
    try {
      gson.fromJson("true", token.getType());
      throw new AssertionError("invalid root accepted");
    } catch (JsonSyntaxException expected) { }
    System.out.println("PASS: production token; empty/nested/long/double/boolean/null conversion; invalid root rejected");
  }
}'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--r8", type=Path, required=True)
    parser.add_argument("--gson", type=Path, required=True)
    parser.add_argument("--android-jar", type=Path, required=True)
    parser.add_argument("--classes", type=Path, required=True, help="Compiled app Kotlin class directory")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    suffix = ".exe" if (args.java_home / "bin/java.exe").exists() else ""
    java = args.java_home / ("bin/java" + suffix)
    javac = args.java_home / ("bin/javac" + suffix)

    def run(arguments, log):
        result = subprocess.run([str(arg) for arg in arguments], stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        (args.output / log).write_bytes(result.stdout)
        return result

    source = args.output / "GsonReleaseProbe.java"
    source.write_text(PROBE.replace("TOKEN_CLASS", TOKEN), encoding="utf-8")
    classes = args.output / "classes"
    classes.mkdir()
    compile_result = run([javac, "--release", "8", "-cp", args.gson, "-d", classes, source], "javac.txt")
    assert compile_result.returncode == 0, "Probe compilation failed; see javac.txt"
    program = args.output / "program.jar"
    with zipfile.ZipFile(program, "w") as archive:
        archive.write(classes / "GsonReleaseProbe.class", "GsonReleaseProbe.class")
        token_path = TOKEN.replace(".", "/") + ".class"
        archive.write(args.classes / token_path, token_path)

    production_rules = (Path(__file__).resolve().parents[1] / "app/proguard-rules.pro").read_text(encoding="utf-8")
    control_rules = "\n".join(line for line in production_rules.splitlines() if not (
        line.strip().startswith("-keep class") and "com.google.gson.reflect.TypeToken" in line
    ))
    fixture_rules = '''
-keep class GsonReleaseProbe { public static void main(java.lang.String[]); }
-dontwarn io.nekohasekai.sagernet.**
-dontwarn kotlin.**
-dontwarn java.lang.management.**
-dontwarn sun.misc.**
'''
    # The fixture contains the real anonymous class, not its enclosing Android app.
    results = {}
    for mode, rules in [("negative-control", control_rules), ("production-rules", production_rules)]:
        config = args.output / (mode + ".pro")
        config.write_text(rules + fixture_rules, encoding="utf-8")
        output = args.output / (mode + ".jar")
        optimized = run([java, "-cp", args.r8, "com.android.tools.r8.R8", "--classfile", "--release",
                         "--lib", args.android_jar, "--pg-conf", config, "--output", output, program, args.gson], mode + "-r8.txt")
        assert optimized.returncode == 0, f"R8 failed: {mode}"
        executed = run([java, "-cp", output, "GsonReleaseProbe"], mode + "-run.txt")
        results[mode] = {"r8_exit": optimized.returncode, "run_exit": executed.returncode,
                         "output": executed.stdout.decode("utf-8", errors="replace")}
        if mode == "negative-control":
            assert executed.returncode != 0 and b"Missing type parameter." in executed.stdout, "Negative control did not reproduce the defect"
        else:
            assert executed.returncode == 0, "Optimized production token failed; see production-rules-run.txt"
    (args.output / "results.json").write_text(json.dumps(results, indent=2), encoding="utf-8")
    print("PASS: negative control reproduced; production R8 rules preserved executable generic conversion")


if __name__ == "__main__":
    main()
