import argparse
import os
import re
import subprocess
import sys
import urllib.request
import zipfile
import shutil

APP_NAME = "Omega"
SRC_DIR = "src"                      # исходники лежат в папке src (по одному классу на файл)
JAVA_FILE = f"{APP_NAME}.java"       # запасной вариант: один файл Omega.java рядом со скриптом
CLASSES_DIR = "build_classes"
LIB_DIR = "lib"
DEFAULT_VERSION = "1.0.0"

CLDC_JAR_URL = "https://repo1.maven.org/maven2/org/microemu/cldcapi11/2.0.3/cldcapi11-2.0.3.jar"
MIDP_JAR_URL = "https://repo1.maven.org/maven2/org/microemu/microemu-midp/2.0.4/microemu-midp-2.0.4.jar"
JSR75_JAR_URL = "https://repo1.maven.org/maven2/org/microemu/microemu-jsr-75/2.0.4/microemu-jsr-75-2.0.4.jar"
PROGUARD_JAR_URL = "https://repo1.maven.org/maven2/net/sf/proguard/proguard/4.4/proguard-4.4.jar"

# Строка с версией в окне About внутри Omega.java: new Form("Omega v1.0.9")
ABOUT_RE = re.compile(r'(new\s+Form\(\s*")Omega v[^"]*("\s*\))')
VERSION_RE = re.compile(r"^\s*MIDlet-Version\s*:\s*(\S+)\s*$", re.IGNORECASE | re.MULTILINE)


def java_files():
    """Все исходники: src/*.java, а если такой папки нет, то один Omega.java рядом со скриптом."""
    if os.path.isdir(SRC_DIR):
        return sorted(os.path.join(SRC_DIR, f) for f in os.listdir(SRC_DIR) if f.endswith(".java"))
    return [JAVA_FILE] if os.path.exists(JAVA_FILE) else []


def about_file():
    """Файл, где лежит строка new Form("Omega v...") окна About; None, если не найден."""
    for p in java_files():
        with open(p, "r", encoding="utf-8", errors="replace") as f:
            if ABOUT_RE.search(f.read()):
                return p
    return None


# ---------- Версия ----------

def version_tuple(v):
    nums = [int(x) for x in re.findall(r"\d+", v)]
    return tuple(nums + [0] * (3 - len(nums)))


def read_jad_version(path):
    """Версия из готового .jad (MIDlet-Version) или None, если файла нет или версии в нём нет."""
    if not os.path.exists(path):
        return None
    with open(path, "rb") as f:
        text = f.read().decode("iso-8859-1")
    m = VERSION_RE.search(text)
    return m.group(1) if m else None


def read_java_version():
    """Версия из строки About в Omega.java (например Omega v1.0.9 или v1.09) или None."""
    p = about_file()
    if not p:
        return None
    with open(p, "r", encoding="utf-8", errors="replace") as f:
        m = re.search(r'new\s+Form\(\s*"Omega v([^"]*)"\s*\)', f.read())
    return m.group(1) if m else None


def current_version():
    """Самая свежая версия из .jad обеих сборок; если их нет, то из Omega.java; иначе по умолчанию."""
    found = []
    for name in (f"{APP_NAME}.jad", f"{APP_NAME}_s40_fix.jad"):
        v = read_jad_version(name)
        if v:
            found.append(v)
    if found:
        return max(found, key=version_tuple)
    jv = read_java_version()
    if jv:
        nums = re.findall(r"\d+", jv)
        # Старый формат "1.09" читаем как 1.0.9
        if len(nums) == 2 and len(nums[1]) == 2:
            return f"{nums[0]}.{nums[1][0]}.{int(nums[1][1])}"
        return ".".join(nums) if nums else DEFAULT_VERSION
    return DEFAULT_VERSION


def bump(v):
    parts = [int(x) for x in re.findall(r"\d+", v)] or [1, 0, 0]
    while len(parts) < 3:
        parts.append(0)
    parts[-1] += 1
    return ".".join(str(p) for p in parts)


def write_java_version(ver):
    """Меняет цифру версии в строке About файла Omega.java. Возвращает True, если строка найдена."""
    p = about_file()
    if not p:
        return False
    with open(p, "r", encoding="utf-8", errors="replace", newline="") as f:
        src = f.read()
    new_src, n = ABOUT_RE.subn(lambda m: f'{m.group(1)}Omega v{ver}{m.group(2)}', src, count=1)
    if n == 0:
        return False
    with open(p, "w", encoding="utf-8", newline="") as f:
        f.write(new_src)
    return True


def choose_version(args):
    cur = current_version()
    if args.version:
        return args.version
    if args.bump:
        return bump(cur)
    if args.no_bump or not sys.stdin.isatty():
        return cur
    nxt = bump(cur)
    ans = input(f"Текущая версия {cur}. Поднять до {nxt}? [y = да / N = нет, оставить {cur} / или введи свою]: ").strip()
    if ans.lower() in ("y", "yes", "д", "да"):
        return nxt
    if re.fullmatch(r"\d+(\.\d+){1,3}", ans):
        return ans
    return cur


# ---------- Зависимости ----------

def download_deps():
    os.makedirs(LIB_DIR, exist_ok=True)
    cldc_path = os.path.join(LIB_DIR, "cldcapi11.jar")
    midp_path = os.path.join(LIB_DIR, "midp20.jar")
    jsr75_path = os.path.join(LIB_DIR, "jsr75.jar")
    proguard_path = os.path.join(LIB_DIR, "proguard.jar")

    headers = {'User-Agent': 'Mozilla/5.0'}
    deps = [
        (CLDC_JAR_URL, cldc_path),
        (MIDP_JAR_URL, midp_path),
        (JSR75_JAR_URL, jsr75_path),
        (PROGUARD_JAR_URL, proguard_path)
    ]

    for url, path in deps:
        if not os.path.exists(path) or os.path.getsize(path) < 1000:
            print(f"[-] Скачиваю {os.path.basename(path)}...")
            req = urllib.request.Request(url, headers=headers)
            with urllib.request.urlopen(req) as resp, open(path, 'wb') as f:
                f.write(resp.read())

    return cldc_path, midp_path, jsr75_path, proguard_path


# ---------- Сборка ----------

def manifest_attributes(version, is_s40):
    attributes = [
        ("Manifest-Version", "1.0"),
        ("Created-By", "1.0 (Sun Microsystems Inc.)"),
        ("MIDlet-Name", APP_NAME),
        ("MIDlet-Version", version),
        ("MIDlet-Vendor", "Bebrarush"),
        ("MicroEdition-Profile", "MIDP-2.0"),
        ("MicroEdition-Configuration", "CLDC-1.1"),
    ]
    if is_s40:
        # Строгий набор для S40 без лишних тач-тегов, ломающих валидатор
        attributes.append(("MIDlet-1", f"{APP_NAME}, icon.png, {APP_NAME}"))
    else:
        # Симбиан-версия со старыми параметрами
        attributes.extend([
            ("Ant-Version", "Apache Ant 1.7.1"),
            ("MIDlet-1", f"{APP_NAME}, icon.png, {APP_NAME}"),
            ("Nokia-Touch-Support", "true"),
            ("UseNativeTextInput", "false"),
            ("Touch-Screen-Device", "touch"),
            ("MIDlet-Permissions", "javax.microedition.io.Connector.file.read,javax.microedition.io.Connector.file.write"),
            ("MIDlet-Permissions-Opt", "javax.microedition.io.Connector.http"),
        ])
    return attributes


def create_jar(jar_name, target_dir, version, wtk_style=False, is_s40=False, write_jad=True):
    jad_name = jar_name.replace(".jar", ".jad")
    attributes = manifest_attributes(version, is_s40)

    manifest_lines = [f"{k}: {v}" for k, v in attributes]
    manifest_lines.append("")  # Пустая строка в конце обязательна

    # Строго бинарная запись в iso-8859-1 без дублирования \r\r\n
    manifest_bytes = "\r\n".join(manifest_lines).encode("iso-8859-1")

    with zipfile.ZipFile(jar_name, "w", zipfile.ZIP_DEFLATED) as jar:
        # MANIFEST.MF всегда идет самым первым в архиве
        jar.writestr("META-INF/MANIFEST.MF", manifest_bytes)

        if wtk_style:
            all_files = []
            for root, dirs, files in os.walk(target_dir):
                for file in files:
                    if file.endswith(".class"):
                        full_path = os.path.join(root, file)
                        arcname = os.path.relpath(full_path, target_dir).replace("\\", "/")
                        all_files.append((full_path, arcname))

            for file in os.listdir("."):
                if file.lower().endswith(".png"):
                    all_files.append((file, file))

            all_files.sort(key=lambda x: x[1])
            for src, arcname in all_files:
                jar.write(src, arcname)
        else:
            for root, dirs, files in os.walk(target_dir):
                for file in files:
                    if file.upper() == "MANIFEST.MF" and os.path.basename(root).upper() == "META-INF":
                        continue  # манифест уже записан выше
                    full_path = os.path.join(root, file)
                    arcname = os.path.relpath(full_path, target_dir).replace("\\", "/")
                    jar.write(full_path, arcname)

            for file in os.listdir("."):
                if file.lower().endswith(".png"):
                    jar.write(file, file)

    if not write_jad:
        return
    jar_size = os.path.getsize(jar_name)
    jad_lines = [f"{k}: {v}" for k, v in attributes]
    jad_lines.append(f"MIDlet-Jar-URL: {jar_name}")
    jad_lines.append(f"MIDlet-Jar-Size: {jar_size}")
    jad_lines.append("")

    jad_bytes = "\r\n".join(jad_lines).encode("iso-8859-1")
    with open(jad_name, "wb") as f:
        f.write(jad_bytes)


def build_variant(final_jar, version, proguard, bootclasspath, is_s40, wtk_style):
    """Одна сборка: сырой jar, преверификация ProGuard (обязательна), финальная упаковка. True при успехе."""
    tag = final_jar.replace(".jar", "")
    temp_raw = f"{tag}_temp_raw.jar"
    pre_jar = f"{tag}_preverified.jar"
    extract_dir = f"build_{tag}"

    create_jar(temp_raw, CLASSES_DIR, version, wtk_style=False, is_s40=is_s40, write_jad=False)

    print(f"[-] Преверификация ProGuard: {final_jar}...")
    pg_cmd = [
        "java", "-jar", proguard,
        "-injars", temp_raw,
        "-outjars", pre_jar,
        "-libraryjars", bootclasspath,
        "-microedition",
        "-dontobfuscate",
        "-dontshrink",
        "-dontoptimize",
        "-keep", f"public class {APP_NAME} extends javax.microedition.midlet.MIDlet {{ public *; }}"
    ]
    pg_res = subprocess.run(pg_cmd, capture_output=True, text=True)
    if os.path.exists(temp_raw):
        os.remove(temp_raw)

    if pg_res.returncode != 0 or not os.path.exists(pre_jar):
        print(f"[!] Ошибка ProGuard для {final_jar} (сборка без преверификации не выпускается):\n", pg_res.stderr or pg_res.stdout)
        if os.path.exists(pre_jar):
            os.remove(pre_jar)
        return False

    if os.path.exists(extract_dir):
        shutil.rmtree(extract_dir)
    os.makedirs(extract_dir, exist_ok=True)
    with zipfile.ZipFile(pre_jar, "r") as zf:
        zf.extractall(extract_dir)
    os.remove(pre_jar)

    create_jar(final_jar, extract_dir, version, wtk_style=wtk_style, is_s40=is_s40)
    shutil.rmtree(extract_dir)
    print(f"[+] Готово: {final_jar} + {tag}.jad (версия {version}, преверифицирован)")
    return True


def main():
    parser = argparse.ArgumentParser(description="Сборка Omega: Symbian (Omega.jar) и S40 (Omega_s40_fix.jar), обе с преверификацией")
    g = parser.add_mutually_exclusive_group()
    g.add_argument("--bump", action="store_true", help="поднять версию без вопроса")
    g.add_argument("--no-bump", action="store_true", help="оставить текущую версию без вопроса")
    g.add_argument("--version", help="задать версию вручную, например 1.1.0")
    args = parser.parse_args()

    print("=== Omega Ultimate Builder ===")
    sources = java_files()
    if not sources:
        print(f"[!] Ошибка: не найдены исходники (папка {SRC_DIR}/*.java или {JAVA_FILE})!")
        return
    print(f"[i] Исходников: {len(sources)}")

    version = choose_version(args)
    print(f"[i] Версия сборки: {version}")

    # Версия в окне About меняется только когда она действительно изменилась, и до компиляции
    if read_java_version() != version:
        if write_java_version(version):
            print(f'[+] В {about_file()} обновлена строка new Form("Omega v{version}")')
        else:
            print('[!] Не найдена строка new Form("Omega v...") - версия в About не изменена')

    if os.path.exists(CLASSES_DIR):
        shutil.rmtree(CLASSES_DIR)
    os.makedirs(CLASSES_DIR, exist_ok=True)

    cldc, midp, jsr75, proguard = download_deps()
    sep = ';' if os.name == 'nt' else ':'
    bootclasspath = f"{cldc}{sep}{midp}{sep}{jsr75}"

    print("[-] Компиляция Java 1.3...")
    javac_cmd = [
        "javac",
        "-source", "1.3",
        "-target", "1.3",
        "-bootclasspath", bootclasspath,
        "-d", CLASSES_DIR,
    ] + sources
    res = subprocess.run(javac_cmd, capture_output=True, text=True)
    if res.returncode != 0:
        print("[!] Ошибка javac:\n", res.stderr)
        return

    ok_sym = build_variant(f"{APP_NAME}.jar", version, proguard, bootclasspath, is_s40=False, wtk_style=False)
    ok_s40 = build_variant(f"{APP_NAME}_s40_fix.jar", version, proguard, bootclasspath, is_s40=True, wtk_style=True)

    if ok_sym and ok_s40:
        print("[+] Обе сборки готовы. Для обновления по воздуху загрузи в релиз GitHub оба .jar и оба .jad.")
    else:
        print("[!] Часть сборок не создана, смотри ошибки выше.")


if __name__ == "__main__":
    main()
