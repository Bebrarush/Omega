import os
import subprocess
import urllib.request
import zipfile
import shutil

APP_NAME = "Omega"
JAVA_FILE = f"{APP_NAME}.java"
CLASSES_DIR = "build_classes"
LIB_DIR = "lib"

CLDC_JAR_URL = "https://repo1.maven.org/maven2/org/microemu/cldcapi11/2.0.3/cldcapi11-2.0.3.jar"
MIDP_JAR_URL = "https://repo1.maven.org/maven2/org/microemu/microemu-midp/2.0.4/microemu-midp-2.0.4.jar"
JSR75_JAR_URL = "https://repo1.maven.org/maven2/org/microemu/microemu-jsr-75/2.0.4/microemu-jsr-75-2.0.4.jar"
PROGUARD_JAR_URL = "https://repo1.maven.org/maven2/net/sf/proguard/proguard/4.4/proguard-4.4.jar"

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

def create_jar(jar_name, target_dir, wtk_style=False, is_s40=False):
    jad_name = jar_name.replace(".jar", ".jad")
    
    # Базовые атрибуты для всех
    attributes = [
        ("Manifest-Version", "1.0"),
        ("Created-By", "1.0 (Sun Microsystems Inc.)"),
        ("MIDlet-Name", APP_NAME),
        ("MIDlet-Version", "1.0.8"),
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

    manifest_lines = [f"{k}: {v}" for k, v in attributes]
    manifest_lines.append("") # Пустая строка в конце обязательна
    
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
                    full_path = os.path.join(root, file)
                    arcname = os.path.relpath(full_path, target_dir)
                    jar.write(full_path, arcname)

            for file in os.listdir("."):
                if file.lower().endswith(".png"):
                    jar.write(file, file)

    jar_size = os.path.getsize(jar_name)
    jad_lines = [f"{k}: {v}" for k, v in attributes]
    jad_lines.append(f"MIDlet-Jar-URL: {jar_name}")
    jad_lines.append(f"MIDlet-Jar-Size: {jar_size}")
    jad_lines.append("")

    jad_bytes = "\r\n".join(jad_lines).encode("iso-8859-1")
    with open(jad_name, "wb") as f:
        f.write(jad_bytes)

def main():
    print(f"=== Omega Ultimate Builder ===")
    if not os.path.exists(JAVA_FILE):
        print(f"[!] Ошибка: {JAVA_FILE} не найден!")
        return

    if os.path.exists(CLASSES_DIR): shutil.rmtree(CLASSES_DIR)
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
        JAVA_FILE
    ]
    res = subprocess.run(javac_cmd, capture_output=True, text=True)
    if res.returncode != 0:
        print("[!] Ошибка javac:\n", res.stderr)
        return

    # 1. Сборка стандартного Omega.jar для Symbian / S60 (без изменений и без фиксов S40)
    create_jar("Omega.jar", CLASSES_DIR, wtk_style=False, is_s40=False)
    print("[+] Собрано: Omega.jar (для Symbian/S60)")

    # 2. Создаем сырой jar для преверификации ProGuard под S40
    temp_raw_jar = "temp_raw.jar"
    create_jar(temp_raw_jar, CLASSES_DIR, wtk_style=False, is_s40=True)

    print("[-] Выполнение преверификации через ProGuard для S40...")
    pg_cmd = [
        "java", "-jar", proguard,
        "-injars", temp_raw_jar,
        "-outjars", "Omega_s40_fix_raw.jar",
        "-libraryjars", bootclasspath,
        "-microedition",
        "-dontobfuscate",
        "-dontshrink",
        "-dontoptimize",
        "-keep", f"public class {APP_NAME} extends javax.microedition.midlet.MIDlet {{ public *; }}"
    ]
    
    pg_res = subprocess.run(pg_cmd, capture_output=True, text=True)
    if os.path.exists(temp_raw_jar): os.remove(temp_raw_jar)

    if pg_res.returncode == 0 and os.path.exists("Omega_s40_fix_raw.jar"):
        print("[-] Финальная WTK-упаковка с детерминированной структурой под S40...")
        extract_dir = "build_fixed"
        if os.path.exists(extract_dir): shutil.rmtree(extract_dir)
        os.makedirs(extract_dir, exist_ok=True)
        
        with zipfile.ZipFile("Omega_s40_fix_raw.jar", "r") as zf:
            zf.extractall(extract_dir)
        
        if os.path.exists("Omega_s40_fix_raw.jar"): 
            os.remove("Omega_s40_fix_raw.jar")
            
        # Создаем итоговый файл Omega_s40_fix.jar по строгим стандартам WTK для S40
        create_jar("Omega_s40_fix.jar", extract_dir, wtk_style=True, is_s40=True)
        shutil.rmtree(extract_dir)
        print("[+] Успешно создана финальная версия: Omega_s40_fix.jar (WTK-compliant для S40)")
    else:
        print("[!] Ошибка ProGuard:\n", pg_res.stderr)

if __name__ == "__main__":
    main()