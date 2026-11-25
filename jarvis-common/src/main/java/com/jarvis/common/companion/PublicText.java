package com.jarvis.common.companion;
import java.util.regex.Pattern;
/** Defense-in-depth redaction boundary, not a memory relevance classifier. */
public final class PublicText {
    private static final Pattern UNSAFE=Pattern.compile("(?is)(-----BEGIN .*PRIVATE KEY|(?:password|passwd|api[_ -]?key|access[_ -]?token|secret)\\s*[:=]\\s*\\S+|bearer\\s+[A-Za-z0-9._~+/-]{12,}|sk-[A-Za-z0-9_-]{16,}|chain.of.thought|hidden reasoning|system prompt)");
    private PublicText(){}
    public static boolean safe(String text){return text!=null&&!UNSAFE.matcher(text).find()&&text.chars().noneMatch(c->c<32&&c!=10&&c!=13&&c!=9);}
    public static String required(String text,int max){if(text==null||text.isBlank()||text.length()>max||!safe(text))throw new IllegalArgumentException("Invalid or unsafe public text");return text.strip();}
    public static String optional(String text,int max){return text==null||text.isBlank()?null:required(text,max);}
}
