// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.diagnostic;

import com.intellij.ide.IdeCoreBundle;
import com.intellij.openapi.application.ApplicationNamesInfo;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.NlsContexts;
import com.intellij.openapi.util.NlsSafe;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.util.system.CpuArch;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.PropertyKey;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class VMOptions {
  private static final Logger LOG = Logger.getInstance(VMOptions.class);

  public enum MemoryKind {
    HEAP("Xmx", "", "change.memory.max.heap"),
    MIN_HEAP("Xms", "", "change.memory.min.heap"),
    METASPACE("XX:MaxMetaspaceSize", "=", "change.memory.metaspace"),
    CODE_CACHE("XX:ReservedCodeCacheSize", "=", "change.memory.code.cache");

    public final @NlsSafe String optionName;
    public final String option;
    private final String labelKey;

    MemoryKind(String name, String separator, @PropertyKey(resourceBundle = "messages.IdeCoreBundle") String key) {
      optionName = name;
      option = '-' + name + separator;
      labelKey = key;
    }

    public @NlsContexts.Label String label() {
      return IdeCoreBundle.message(labelKey);
    }
  }

  public static int readOption(@NotNull MemoryKind kind, boolean effective) {
    List<String> arguments;
    if (effective) {
      arguments = ManagementFactory.getRuntimeMXBean().getInputArguments();
    }
    else {
      Path file = getWriteFile();
      if (file == null || !Files.exists(file)) {
        return -1;
      }

      try {
        arguments = FileUtil.loadLines(file.toFile());
      }
      catch (IOException e) {
        LOG.warn(e);
        return -1;
      }
    }

    for (String argument : arguments) {
      if (argument.startsWith(kind.option)) {
        try {
          return (int)(parseMemoryOption(argument.substring(kind.option.length())) >> 20);
        }
        catch (IllegalArgumentException e) {
          LOG.info(e);
          break;
        }
      }
    }

    return -1;
  }

  /**
   * Parses a Java VM memory option string (such as "-Xmx") and returns its numeric value, in bytes.
   * See <a href="https://docs.oracle.com/en/java/javase/16/docs/specs/man/java.html#extra-options-for-java">'java' command manual</a>
   * for the syntax.
   *
   * @throws IllegalArgumentException when either a number or a unit is invalid
   */
  public static long parseMemoryOption(@NotNull String strValue) throws IllegalArgumentException {
    int p = 0;
    while (p < strValue.length() && StringUtil.isDecimalDigit(strValue.charAt(p))) p++;
    long numValue = Long.parseLong(strValue.substring(0, p));
    if (p < strValue.length()) {
      String unit = strValue.substring(p);
      if ("k".equalsIgnoreCase(unit)) numValue <<= 10;
      else if ("m".equalsIgnoreCase(unit)) numValue <<= 20;
      else if ("g".equalsIgnoreCase(unit)) numValue <<= 30;
      else throw new IllegalArgumentException("Invalid unit: " + unit);
    }
    return numValue;
  }

  public static void writeOption(@NotNull MemoryKind option, int value) {
    String optionValue = option.option + value + "m";
    writeGeneralOption(Pattern.compile(option.option + "(\\d*)([a-zA-Z]*)"), optionValue);
  }

  public static void writeOption(@NotNull String option, @NotNull String separator, @NotNull String value) {
    writeGeneralOption(Pattern.compile("-D" + option + separator + "(true|false)*([a-zA-Z0-9]*)"), "-D" + option + separator + value);
  }

  public static void writeEnableCDSArchiveOption(@NotNull final String archivePath) {
    writeGeneralOptions(
      Function.<String>identity()
        .andThen(replaceOrAddOption(Pattern.compile("-Xshare:.*"), "-Xshare:auto"))
        .andThen(replaceOrAddOption(Pattern.compile("-XX:\\+UnlockDiagnosticVMOptions"), "-XX:+UnlockDiagnosticVMOptions"))
        .andThen(replaceOrAddOption(Pattern.compile("-XX:SharedArchiveFile=.*"), "-XX:SharedArchiveFile=" + archivePath))
    );
  }

  public static void writeDisableCDSArchiveOption() {
    writeGeneralOptions(
      Function.<String>identity()
        .andThen(replaceOrAddOption(Pattern.compile("-Xshare:.*\\r?\\n?"), ""))
        // we cannot remove "-XX:+UnlockDiagnosticVMOptions", we do not know the reason it was included
        .andThen(replaceOrAddOption(Pattern.compile("-XX:SharedArchiveFile=.*\\r?\\n?"), ""))
    );
  }

  private static void writeGeneralOption(@NotNull Pattern pattern, @NotNull String value) {
    writeGeneralOptions(replaceOrAddOption(pattern, value));
  }

  @NotNull
  private static Function<String, String> replaceOrAddOption(@NotNull Pattern pattern, @NotNull String value) {
    return content -> {
      if (!StringUtil.isEmptyOrSpaces(content)) {
        Matcher m = pattern.matcher(content);
        if (m.find()) {
          StringBuilder b = new StringBuilder();
          m.appendReplacement(b, Matcher.quoteReplacement(value));
          m.appendTail(b);
          content = b.toString();
        }
        else if (!StringUtil.isEmptyOrSpaces(value)) {
          content = StringUtil.trimTrailing(content) + System.lineSeparator() + value;
        }
      }
      else {
        content = value;
      }

      return content;
    };
  }

  private static void writeGeneralOptions(@NotNull Function<? super String, String> transformContent) {
    Path path = getWriteFile();
    if (path == null) {
      LOG.warn("VM options file not configured");
      return;
    }

    File file = path.toFile();
    try {
      String content = file.exists() ? FileUtil.loadFile(file) : read();
      content = transformContent.apply(content);

      if (file.exists()) {
        FileUtil.setReadOnlyAttribute(file.getPath(), false);
      }
      else {
        FileUtil.ensureExists(file.getParentFile());
      }

      FileUtil.writeToFile(file, content);
    }
    catch (IOException e) {
      LOG.warn(e);
    }
  }

  public static boolean canWriteOptions() {
    return getWriteFile() != null;
  }

  @Nullable
  public static String read() {
    try {
      Path newFile = getWriteFile();
      if (newFile != null && Files.exists(newFile)) {
        return FileUtil.loadFile(newFile.toFile());
      }

      String vmOptionsFile = System.getProperty("jb.vmOptionsFile");
      if (vmOptionsFile != null) {
        return FileUtil.loadFile(new File(vmOptionsFile));
      }
    }
    catch (IOException e) {
      LOG.info(e);
    }

    return null;
  }

  public static @Nullable Path getWriteFile() {
    String vmOptionsFile = System.getProperty("jb.vmOptionsFile");
    if (vmOptionsFile == null) {
      // launchers should specify a path to a VM options file used to configure a JVM
      return null;
    }

    vmOptionsFile = new File(vmOptionsFile).getAbsolutePath();
    if (!PathManager.isUnderHomeDirectory(vmOptionsFile)) {
      // a file is located outside the IDE installation - meaning it is safe to overwrite
      return Paths.get(vmOptionsFile);
    }

    String location = PathManager.getCustomOptionsDirectory();
    if (location == null) {
      return null;
    }

    return Paths.get(location, getCustomVMOptionsFileName());
  }

  @NotNull
  public static String getCustomVMOptionsFileName() {
    String fileName = ApplicationNamesInfo.getInstance().getScriptName();
    if (!SystemInfo.isMac && CpuArch.isIntel64()) fileName += "64";
    if (SystemInfo.isWindows) fileName += ".exe";
    fileName += ".vmoptions";
    return fileName;
  }
}
