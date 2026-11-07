package com.trough.hot;
public class TranscriptRowIdentityCheck {
 static int checks;static void check(boolean value){checks++;if(!value)throw new AssertionError(checks);}
 public static void main(String[] args){
 check(TranscriptRowIdentity.matches(new String[]{"assistant","same"},"assistant","same"));
 check(!TranscriptRowIdentity.matches(new String[]{"assistant","old"},"assistant","new"));
 check(!TranscriptRowIdentity.matches(new String[]{"user","same"},"assistant","same"));
 check(!TranscriptRowIdentity.matches(null,"user","same"));
 check(!TranscriptRowIdentity.matches("same","user","same"));
 check(!TranscriptRowIdentity.matches(new String[]{"user"},"user","same"));
 check(TranscriptRowIdentity.matches(new String[]{"user",null},"user",null));
 check(!TranscriptRowIdentity.matches(new String[]{"assistant","Aa"},"assistant","BB"));
 check(!TranscriptRowIdentity.matches(new String[]{"work_assistant","same"},"assistant","same"));
 System.out.println("PASS "+checks+" exact row identity cases");
 }
}
