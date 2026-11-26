package com.jarvis.common.ai;

import java.util.function.Consumer;

/** Incremental JSON lexer: extracts only root action and root answer strings, across escape boundaries. */
public final class JsonAnswerStream {
    private final Consumer<String> visible;
    private final java.util.Set<String> rootFields=new java.util.HashSet<>();
    private final StringBuilder key=new StringBuilder(),value=new StringBuilder(),answer=new StringBuilder();
    private String field="",action="";private int depth;private boolean inString,isKey,expectKey,escape;private int unicodeDigits,unicodeValue;private char pendingHigh;private int emitted;
    public JsonAnswerStream(Consumer<String> visible){this.visible=visible;}
    public String answer(){return answer.toString();}
    public String action(){return action;}
    public void accept(String delta){
        for(int i=0;i<delta.length();i++){
            char c=delta.charAt(i);
            if(inString){
                if(unicodeDigits>0){int digit=Character.digit(c,16);if(digit<0)throw new IllegalArgumentException("Invalid JSON escape");unicodeValue=(unicodeValue<<4)|digit;if(--unicodeDigits==0)decoded((char)unicodeValue);continue;}
                if(escape){escape=false;switch(c){case 'u'-> {unicodeDigits=4;unicodeValue=0;}case 'n'->decoded('\n');case 'r'->decoded('\r');case 't'->decoded('\t');case 'b'->decoded('\b');case 'f'->decoded('\f');case '"','\\','/'->decoded(c);default->throw new IllegalArgumentException("Invalid JSON escape");}continue;}
                if(c=='\\'){escape=true;continue;}
                if(c=='"'){
                    if(pendingHigh!=0)throw new IllegalArgumentException("Unpaired Unicode surrogate");
                    inString=false;if(isKey){field=key.toString();if(!rootFields.add(field))throw new IllegalArgumentException("Duplicate root field");expectKey=false;}else if(depth==1&&field.equals("action")){action=value.toString();flush();}continue;
                }
                decoded(c);continue;
            }
            if(c=='{'||c=='['){depth++;if(depth==1)expectKey=true;}
            else if(c=='}'||c==']')depth--;
            else if(c==','&&depth==1)expectKey=true;
            else if(c=='"'){inString=true;isKey=depth==1&&expectKey;key.setLength(0);value.setLength(0);}
        }
        flush();
    }
    private void decoded(char c){
        if(isKey){key.append(c);return;}
        if(depth!=1)return;
        value.append(c);
        if(!field.equals("answer"))return;
        if(Character.isHighSurrogate(c)){if(pendingHigh!=0)throw new IllegalArgumentException("Unpaired surrogate");pendingHigh=c;return;}
        if(pendingHigh!=0){if(!Character.isLowSurrogate(c))throw new IllegalArgumentException("Unpaired surrogate");answer.append(pendingHigh);pendingHigh=0;}
        else if(Character.isLowSurrogate(c))throw new IllegalArgumentException("Unpaired surrogate");
        answer.append(c);
    }
    private void flush(){if(action.equals("DIRECT")&&answer.length()>emitted){String text=answer.substring(emitted);emitted=answer.length();visible.accept(text);}}
}
