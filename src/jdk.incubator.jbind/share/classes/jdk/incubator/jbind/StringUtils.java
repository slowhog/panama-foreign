package jdk.incubator.jbind;

public class StringUtils {
    /**
     * Escapes each character in a string that has an escape sequence or
     * is non-printable ASCII.  Leaves non-ASCII characters alone.
     */
    public static String quote(String s) {
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            buf.append(quote(s.charAt(i)));
        }
        return buf.toString();
    }

    /**
     * Escapes a character if it has an escape sequence or is
     * non-printable ASCII.  Leaves non-ASCII characters alone.
     */
    public static String quote(char ch) {
        switch (ch) {
            case '\b':  return "\\b";
            case '\f':  return "\\f";
            case '\n':  return "\\n";
            case '\r':  return "\\r";
            case '\t':  return "\\t";
            case '\'':  return "\\'";
            case '\"':  return "\\\"";
            case '\\':  return "\\\\";
            default:
                return (isPrintableAscii(ch))
                        ? String.valueOf(ch)
                        : String.format("\\u%04x", (int) ch);
        }
    }

    /**
     * Is a character printable ASCII?
     */
    private static boolean isPrintableAscii(char ch) {
        return ch >= ' ' && ch <= '~';
    }

}
