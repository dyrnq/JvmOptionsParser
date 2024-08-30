
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;


public class JvmOptionsParser {


    private static final String JAVA_VERSION = System.getProperty("java.version");
    private static final Pattern PATTERN = Pattern.compile("((?<start>\\d+)(?<range>-)?(?<end>\\d+)?:)?(?<option>-.*)$");

    private static final String[] ENV_NAMES = {"ES_JAVA_OPTS", "JAVA_OPTS", "JVM_OPTS", "JAVA_OPTIONS", "JVM_OPTIONS"};

    static int getJavaMajorVersion() {
        return getJavaMajorVersion(JAVA_VERSION);
    }

    /***
     * <a href="https://github.com/estekhin/awaitility/blob/322f87757955395c1dc69c86ee9ce8faf1577ce0/awaitility/src/main/java/org/awaitility/core/JavaVersionDetector.java">...</a>
     * @param javaVersion
     */
    static int getJavaMajorVersion(String javaVersion) {
        final String normalizedJavaVersion;
        if (javaVersion == null || javaVersion.isEmpty()) {
            // Fallback to java 8
            normalizedJavaVersion = "8";
        } else if (javaVersion.startsWith("1.")) {
            normalizedJavaVersion = javaVersion.substring(2, 3);
        } else if (javaVersion.indexOf('.') != -1) {
            normalizedJavaVersion = javaVersion.substring(0, javaVersion.indexOf('.'));
        } else if (javaVersion.indexOf('-') != -1) {
            normalizedJavaVersion = javaVersion.substring(0, javaVersion.indexOf('-'));
        } else {
            normalizedJavaVersion = javaVersion;
        }
        return Integer.parseInt(normalizedJavaVersion);
    }


    static List<String> substitutePlaceholders(final List<String> jvmOptions, final Map<String, String> substitutions) {
        final Map<String, String> placeholderSubstitutions = substitutions.entrySet()
                .stream()
                .collect(Collectors.toMap(e -> "${" + e.getKey() + "}", Map.Entry::getValue));
        return jvmOptions.stream().map(jvmOption -> {
            String actualJvmOption = jvmOption;
            int start = jvmOption.indexOf("${");
            if (start >= 0 && jvmOption.indexOf('}', start) > 0) {
                for (final Map.Entry<String, String> placeholderSubstitution : placeholderSubstitutions.entrySet()) {
                    actualJvmOption = actualJvmOption.replace(placeholderSubstitution.getKey(), placeholderSubstitution.getValue());
                }
            }
            return actualJvmOption;
        }).collect(Collectors.toList());
    }

    static void parse(
            final int javaMajorVersion,
            final BufferedReader br,
            final JvmOptionConsumer jvmOptionConsumer,
            final InvalidLineConsumer invalidLineConsumer
    ) throws IOException {
        int lineNumber = 0;
        while (true) {
            final String line = br.readLine();
            lineNumber++;
            if (line == null) {
                break;
            }
            if (line.startsWith("#")) {
                // lines beginning with "#" are treated as comments
                continue;
            }
            if (line.matches("\\s*")) {
                // skip blank lines
                continue;
            }
            final Matcher matcher = PATTERN.matcher(line);
            if (matcher.matches()) {
                final String start = matcher.group("start");
                final String end = matcher.group("end");
                if (start == null) {
                    // no range present, unconditionally apply the JVM option
                    jvmOptionConsumer.accept(line);
                } else {
                    final int lower;
                    try {
                        lower = Integer.parseInt(start);
                    } catch (final NumberFormatException e) {
                        invalidLineConsumer.accept(lineNumber, line);
                        continue;
                    }
                    final int upper;
                    if (matcher.group("range") == null) {
                        // no range is present, apply the JVM option to the specified major version only
                        upper = lower;
                    } else if (end == null) {
                        // a range of the form \\d+- is present, apply the JVM option to all major versions larger than the specified one
                        upper = Integer.MAX_VALUE;
                    } else {
                        // a range of the form \\d+-\\d+ is present, apply the JVM option to the specified range of major versions
                        try {
                            upper = Integer.parseInt(end);
                        } catch (final NumberFormatException e) {
                            invalidLineConsumer.accept(lineNumber, line);
                            continue;
                        }
                        if (upper < lower) {
                            invalidLineConsumer.accept(lineNumber, line);
                            continue;
                        }
                    }
                    if (lower <= javaMajorVersion && javaMajorVersion <= upper) {
                        jvmOptionConsumer.accept(matcher.group("option"));
                    }
                }
            } else {
                invalidLineConsumer.accept(lineNumber, line);
            }
        }
    }

    /**
     * The main entry point. The exit code is 0 if the JVM options were successfully parsed, otherwise the exit code is 1. If an improperly
     * formatted line is discovered, the line is output to standard error.
     *
     * @param args the args to the program which should consist of a single option, the path to ES_PATH_CONF
     */
    public static void main(final String[] args) throws InterruptedException, IOException {
        if (args.length < 1) {
            throw new IllegalArgumentException("expected one argument specifying path to ES_PATH_CONF but was " + Arrays.toString(args));
        }

        final JvmOptionsParser parser = new JvmOptionsParser();

        final Map<String, String> substitutions = new HashMap<>();

        final String environmentPathConf = System.getenv("ES_PATH_CONF");
        if (environmentPathConf != null) {
            substitutions.put("ES_PATH_CONF", environmentPathConf);
        }

        try {
            final List<String> jvmOptions = parser.jvmOptions(
                    Paths.get(args[0]),
                    substitutions
            );
            Launchers.outPrintln(String.join(" ", jvmOptions));
        } catch (final JvmOptionsFileParserException e) {
            final String errorMessage = String.format(
                    Locale.ROOT,
                    "encountered [%d] error%s parsing [%s]",
                    e.invalidLines().size(),
                    e.invalidLines().size() == 1 ? "" : "s",
                    e.jvmOptionsFile()
            );
            Launchers.errPrintln(errorMessage);
            int count = 0;
            for (final Map.Entry<Integer, String> entry : e.invalidLines().entrySet()) {
                count++;
                final String message = String.format(
                        Locale.ROOT,
                        "[%d]: encountered improperly formatted JVM option in [%s] on line number [%d]: [%s]",
                        count,
                        e.jvmOptionsFile(),
                        entry.getKey(),
                        entry.getValue()
                );
                Launchers.errPrintln(message);
            }
            Launchers.exit(1);
        }

        Launchers.exit(0);
    }

    /**
     * <a href="https://github.com/elastic/elasticsearch/blob/v7.17.23/distribution/tools/launchers/src/main/java/org/elasticsearch/tools/launchers/JvmOptionsParser.java">...</a>
     *
     * @param config
     * @param substitutions
     * @return
     * @throws InterruptedException
     * @throws IOException
     * @throws JvmOptionsFileParserException
     */
    private List<String> jvmOptions(final Path config, final Map<String, String> substitutions)
            throws InterruptedException, IOException, JvmOptionsFileParserException {

        final List<String> jvmOptions = readJvmOptionsFiles(config);

        for (String envName : ENV_NAMES) {
            String esJavaOpts = System.getenv(envName);
            if (esJavaOpts != null) {
                jvmOptions.addAll(
                        Arrays.stream(esJavaOpts.split("\\s+")).filter(s -> !s.trim().isEmpty()).collect(Collectors.toList())
                );
            }

        }

        final List<String> substitutedJvmOptions = substitutePlaceholders(jvmOptions, Collections.unmodifiableMap(substitutions));
//        final SystemMemoryInfo memoryInfo = new OverridableSystemMemoryInfo(substitutedJvmOptions, new DefaultSystemMemoryInfo());
//        substitutedJvmOptions.addAll(machineDependentHeap.determineHeapSettings(args.nodeSettings(), memoryInfo, substitutedJvmOptions));
//        final List<String> ergonomicJvmOptions = JvmErgonomics.choose(substitutedJvmOptions, args.nodeSettings());
//        final List<String> systemJvmOptions = SystemJvmOptions.systemJvmOptions(args.nodeSettings(), cliSysprops);
//
//        final List<String> apmOptions = APMJvmOptions.apmJvmOptions(args.nodeSettings(), args.secrets(), args.logsDir(), tmpDir);
//
//        final List<String> finalJvmOptions = new ArrayList<>(
//                systemJvmOptions.size() + substitutedJvmOptions.size() + ergonomicJvmOptions.size() + apmOptions.size()
//        );
//        finalJvmOptions.addAll(systemJvmOptions); // add the system JVM options first so that they can be overridden
//        finalJvmOptions.addAll(substitutedJvmOptions);
//        finalJvmOptions.addAll(ergonomicJvmOptions);
//        finalJvmOptions.addAll(apmOptions);

        return substitutedJvmOptions;
    }

    List<String> readJvmOptionsFiles(final Path config) throws IOException, JvmOptionsFileParserException {
        final ArrayList<Path> jvmOptionsFiles = new ArrayList<>();

        Path jvmOptionsDirectory = null;
        if (Files.isDirectory(config)) {
            jvmOptionsFiles.add(config.resolve("jvm.options"));
            jvmOptionsDirectory = config.resolve("jvm.options.d");
        } else if (Files.isRegularFile(config)) {
            jvmOptionsFiles.add(config);
            jvmOptionsDirectory = (config.getParent().resolve("jvm.options.d"));
        }

        if (jvmOptionsDirectory != null && Files.isDirectory(jvmOptionsDirectory)) {
            try (DirectoryStream<Path> jvmOptionsDirectoryStream = Files.newDirectoryStream(jvmOptionsDirectory, "*.options")) {
                // collect the matching JVM options files after sorting them by Path::compareTo
                StreamSupport.stream(jvmOptionsDirectoryStream.spliterator(), false).sorted().forEach(jvmOptionsFiles::add);
            }
        }

        final List<String> jvmOptions = new ArrayList<>();

        for (final Path jvmOptionsFile : jvmOptionsFiles) {
            final SortedMap<Integer, String> invalidLines = new TreeMap<>();
            try (
                    InputStream is = Files.newInputStream(jvmOptionsFile);
                    Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8);
                    BufferedReader br = new BufferedReader(reader)
            ) {
                parse(getJavaMajorVersion(), br, jvmOptions::add, invalidLines::put);
            }
            if (!invalidLines.isEmpty()) {
                throw new JvmOptionsFileParserException(jvmOptionsFile, invalidLines);
            }
        }
        return jvmOptions;
    }


    /**
     * Callback for valid JVM options.
     */
    interface JvmOptionConsumer {
        /**
         * Invoked when a line in the JVM options file matches the specified syntax and the specified major version.
         *
         * @param jvmOption the matching JVM option
         */
        void accept(String jvmOption);
    }

    /**
     * Callback for invalid lines in the JVM options.
     */
    interface InvalidLineConsumer {
        /**
         * Invoked when a line in the JVM options does not match the specified syntax.
         */
        void accept(int lineNumber, String line);
    }

    static class JvmOptionsFileParserException extends Exception {

        private final Path jvmOptionsFile;
        private final SortedMap<Integer, String> invalidLines;

        JvmOptionsFileParserException(final Path jvmOptionsFile, final SortedMap<Integer, String> invalidLines) {
            this.jvmOptionsFile = jvmOptionsFile;
            this.invalidLines = invalidLines;
        }

        Path jvmOptionsFile() {
            return jvmOptionsFile;
        }

        SortedMap<Integer, String> invalidLines() {
            return invalidLines;
        }
    }

    static final class Launchers {

        /**
         * Prints a string and terminates the line on standard output.
         *
         * @param message the message to print
         */
        static void outPrintln(final String message) {
            System.out.println(message);
        }

        /**
         * Prints a string and terminates the line on standard error.
         *
         * @param message the message to print
         */
        static void errPrintln(final String message) {
            System.err.println(message);
        }

        /**
         * Exit the VM with the specified status.
         *
         * @param status the status
         */
        static void exit(final int status) {
            System.exit(status);
        }
    }

}
