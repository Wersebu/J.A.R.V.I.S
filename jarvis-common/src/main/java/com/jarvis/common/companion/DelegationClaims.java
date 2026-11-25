package com.jarvis.common.companion;
/** Conservative guard for execution narration; backend receipts remain the authoritative state. */
public final class DelegationClaims {
    private DelegationClaims(){}
    public static boolean requested(String text){return text!=null&&java.util.regex.Pattern.compile("(?iu)(agent|deleg|background|w tle)").matcher(text).find();}
    public static boolean started(String text){return text!=null&&java.util.regex.Pattern.compile("(?iu)((?<!nie )uruchomi[łl]em.{0,40}agent|(?<!nie )delegowa[łl]em|(?:i |i've )?(?:started|spawned|launched).{0,30}agent)").matcher(text).find();}
}
