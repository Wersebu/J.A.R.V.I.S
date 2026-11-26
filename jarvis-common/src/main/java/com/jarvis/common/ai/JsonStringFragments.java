package com.jarvis.common.ai;

/** JSON string escape decoder retaining state between arbitrary provider chunks. */
public final class JsonStringFragments {
    private boolean escape;private int digits,number;private char high;
    public String accept(String fragment){
        var out=new StringBuilder();
        for(int i=0;i<fragment.length();i++){
            char c=fragment.charAt(i);
            if(digits>0){int n=Character.digit(c,16);if(n<0)throw new IllegalArgumentException("Invalid Unicode escape");number=(number<<4)|n;if(--digits>0)continue;c=(char)number;}
            else if(escape){escape=false;if(c=='u'){digits=4;number=0;continue;}c=switch(c){case 'n'->'\n';case 'r'->'\r';case 't'->'\t';case 'b'->'\b';case 'f'->'\f';case '\\','/','"'->c;default->throw new IllegalArgumentException("Invalid JSON escape");};}
            else if(c=='\\'){escape=true;continue;}
            if(Character.isHighSurrogate(c)){if(high!=0)throw new IllegalArgumentException("Unpaired surrogate");high=c;continue;}
            if(high!=0){if(!Character.isLowSurrogate(c))throw new IllegalArgumentException("Unpaired surrogate");out.append(high);high=0;}else if(Character.isLowSurrogate(c))throw new IllegalArgumentException("Unpaired surrogate");
            out.append(c);
        }
        return out.toString();
    }
}
