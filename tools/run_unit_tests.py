#!/usr/bin/env python3
"""Worker-free JUnit4 runner for this checkout.

Why this exists
---------------
This repository lives at D:\<CJK>. Gradle writes its worker argument files
(gradle-worker-classpath*.txt) into %TEMP%/gradle-home/.tmp using the daemon's
platform charset, and the forked test JVM reads them back under a different
charset, so every classpath entry containing the CJK project path is mangled.
The worker then dies with:

    ClassNotFoundException: worker.org.gradle.process.internal.worker.GradleWorkerMain
    Could not write standard input to Gradle Test Executor 1.

and '--tests' filters never match because the class scan never happens.

This script sidesteps the Gradle worker completely: Gradle still compiles
(compilation is fine), and the tests run on a plain JVM started directly from
Python, where Windows hands the command line over as UTF-16 and no encoding
round-trip exists.

Usage
-----
    python tools/run_unit_tests.py --list
    python tools/run_unit_tests.py com.zhijian.schedule.core.ui.RevealGeometryTest
    python tools/run_unit_tests.py --all

Exit code is JUnitCore's: 0 on success, non-zero when tests fail.
"""

import argparse
import glob
import os
import re
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)

# Gradle user home: honour GRADLE_USER_HOME, else the layout this machine uses.
GRADLE_HOMES = [
    os.environ.get("GRADLE_USER_HOME"),
    r"D:\gradle-home",
    os.path.join(os.path.expanduser("~"), ".gradle"),
]
GRADLE_HOMES = [p for p in GRADLE_HOMES if p and os.path.isdir(p)]

UNIT_TEST_CLASS_DIRS = [
    os.path.join(ROOT, "app", "build", "tmp", "kotlin-classes", "debugUnitTest"),
    os.path.join(
        ROOT, "app", "build", "intermediates", "classes",
        "debugUnitTest", "transformDebugUnitTestClassesWithAsm", "dirs",
    ),
]


def find_java():
    home = os.environ.get("JAVA_HOME")
    if home:
        exe = os.path.join(home, "bin", "java.exe")
        if os.path.isfile(exe):
            return exe
    return "java"


def decode_argfile(path):
    with open(path, "rb") as handle:
        raw = handle.read()
    for encoding in ("gbk", "utf-8", "latin-1"):
        try:
            text = raw.decode(encoding)
        except UnicodeDecodeError:
            continue
        if "-cp" in text:
            return text
    return None


def classpath_from_argfiles():
    """Recover the exact test runtime classpath Gradle last used.

    The argument file is the authoritative list: it is what Gradle itself
    passes to the worker, so it already contains every jar, the android.jar
    stub, kotlin-stdlib, junit and hamcrest. We only have to decode it
    correctly and drop Gradle's own worker bootstrap jar.
    """
    candidates = []
    for home in GRADLE_HOMES:
        for pattern in (os.path.join(home, ".tmp", "gradle-worker-classpath*"),
                        os.path.join(home, "daemon", "*", "*"),
                        os.path.join(home, "workers", "*", "*")):
            candidates.extend(glob.glob(pattern))

    best = None
    for path in candidates:
        if not os.path.isfile(path):
            continue
        text = decode_argfile(path)
        if not text or "debugUnitTest" not in text or "junit" not in text.lower():
            continue
        stamp = os.path.getmtime(path)
        if best is None or stamp > best[0]:
            best = (stamp, path, text)

    if best is None:
        return None, None

    _, path, text = best
    match = re.search(r"-cp\s*\r?\n(.*?)(?:\r?\n|\Z)", text, re.S)
    if not match:
        return None, None
    entries = [item.replace("\\\\", "\\") for item in match.group(1).split(";") if item]
    entries = [item for item in entries
               if not os.path.basename(item).startswith("gradle-worker")]
    return entries, path


def classpath_from_build_dirs():
    """Fallback: build the classpath from the project's own output directories only.

    Enough for tests that touch nothing but the project's own Kotlin code plus
    junit/hamcrest/stdlib, which is the case for pure geometry/algorithm tests.
    """
    entries = [d for d in UNIT_TEST_CLASS_DIRS if os.path.isdir(d)]
    entries.append(os.path.join(ROOT, "app", "build", "tmp", "kotlin-classes", "debug"))
    wanted = ("junit-4.", "hamcrest-core-", "kotlin-stdlib-", "transformed\\android.jar", "mockwebserver-",
              # jw 适配器层：OkHttp 抓取 + okio + jsoup 解析 + kotlinx.serialization/coroutines
              "okhttp-", "okio-", "jsoup-", "kotlinx-serialization", "kotlinx-coroutines")
    for home in GRADLE_HOMES:
        base = os.path.join(home, "caches", "modules-2", "files-2.1")
        if not os.path.isdir(base):
            continue
        for path in glob.glob(os.path.join(base, "**", "*.jar"), recursive=True):
            if any(token in path for token in wanted):
                entries.append(path)
    seen, unique = set(), []
    for item in entries:
        if item not in seen and os.path.exists(item):
            seen.add(item)
            unique.append(item)
    return unique


def resolve_classpath():
    entries, source = classpath_from_argfiles()
    if entries:
        missing = [e for e in entries if not os.path.exists(e)]
        if not missing:
            return entries, source
    return classpath_from_build_dirs(), "project build dirs (fallback)"


def prioritize_own_class_dirs(classpath):
    """把项目自己最新的编译产物提到 classpath 最前。

    旧 gradle run 留下的 worker argfile 可能只认 intermediates 里的 ASM transform 目录，
    它只在 gradle 真正跑测试时刷新——陈旧类会压过刚编译的新类（症状：新用例“消失”）。
    kotlin-classes 的 debug（主类）与 debugUnitTest（测试类）由 compile 任务直接写出，
    永远是最新编译结果。
    """
    front = [entry for entry in UNIT_TEST_CLASS_DIRS if os.path.isdir(entry)]
    main_classes = os.path.join(ROOT, "app", "build", "tmp", "kotlin-classes", "debug")
    if os.path.isdir(main_classes):
        front.append(main_classes)
    rest = [entry for entry in classpath if entry not in front]
    return front + rest


def write_java_argfile(classpath):
    """把 -cp 写进 java @argfile 绕开 Windows 32k 命令行上限。

    项目内条目转相对路径（repo 根为工作目录），argfile 内容因此全 ASCII；
    java 接受正斜杠。返回 argfile 路径。
    """
    entries = []
    for entry in classpath:
        try:
            entry = os.path.relpath(entry)
        except ValueError:
            pass
        entries.append(entry.replace("\\", "/"))
    content = '-cp "' + os.pathsep.join(entries) + '"\n'
    fd, path = tempfile.mkstemp(prefix="junit-cp-", suffix=".argfile")
    with os.fdopen(fd, "w", encoding="ascii", errors="strict") as handle:
        handle.write(content)
    return path


def available_tests():
    found = []
    for directory in UNIT_TEST_CLASS_DIRS:
        if not os.path.isdir(directory):
            continue
        for path in glob.glob(os.path.join(directory, "**", "*Test.class"), recursive=True):
            if "$" in os.path.basename(path):
                continue
            rel = os.path.relpath(path, directory)
            found.append(rel[: -len(".class")].replace(os.sep, "."))
    return sorted(set(found))


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("classes", nargs="*", help="fully qualified test class names")
    parser.add_argument("--list", action="store_true", help="list compiled test classes and exit")
    parser.add_argument("--all", action="store_true", help="run every compiled test class")
    parser.add_argument("--show-classpath", action="store_true", help="print the classpath and exit")
    args = parser.parse_args()

    if args.list:
        tests = available_tests()
        print("%d compiled test classes under debugUnitTest:" % len(tests))
        for name in tests:
            print("  " + name)
        return 0

    classpath, source = resolve_classpath()
    classpath = prioritize_own_class_dirs(classpath)
    if not classpath:
        print("no classpath could be assembled; run:", file=sys.stderr)
        print("  gradlew.bat :app:compileDebugUnitTestKotlin --offline", file=sys.stderr)
        return 2

    if args.show_classpath:
        print("classpath source: " + str(source))
        for entry in classpath:
            print(entry)
        return 0

    tests = list(args.classes)
    if args.all:
        tests = available_tests()
    if not tests:
        parser.error("name at least one test class, or pass --all / --list")

    java = find_java()
    cp = os.pathsep.join(classpath)
    command = [java, "-Dfile.encoding=UTF-8", "-Dsun.jnu.encoding=UTF-8"]
    # Windows CreateProcess 32k 上限：全量 classpath 拼出的命令过长时走 java @argfile。
    # 项目内条目转相对路径，argfile 内容因此全 ASCII（CJK 绝对路径不进 argfile，免受编码坑）。
    if len(cp) > 30000:
        argfile = write_java_argfile(classpath)
        print("classpath via argfile: %s" % argfile)
        command.append("@" + argfile)
    else:
        command += ["-cp", cp]
    command += ["org.junit.runner.JUnitCore"] + tests

    print("classpath source: %s (%d entries)" % (source, len(classpath)))
    print("running: %s" % " ".join(tests))
    completed = subprocess.run(command)
    return completed.returncode


if __name__ == "__main__":
    sys.exit(main())
