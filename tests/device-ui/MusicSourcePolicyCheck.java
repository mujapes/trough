package com.trough.hot;
public class MusicSourcePolicyCheck {
 static int n;static void check(boolean yes){n++;if(!yes)throw new AssertionError(n);}
 public static void main(String[] args){check(MusicSourcePolicy.allowed("https","stream.googlevideo.com",true));check(!MusicSourcePolicy.allowed("https","stream.googlevideo.com",false));check(!MusicSourcePolicy.allowed("http","stream.googlevideo.com",true));check(!MusicSourcePolicy.allowed("https",null,true));check(!MusicSourcePolicy.allowed("https","",true));check(MusicSourcePolicy.allowed("file",null,false));check(MusicSourcePolicy.allowed("content","media",false));check(!MusicSourcePolicy.allowed("content","media",true));check(!MusicSourcePolicy.allowed(null,null,true));System.out.println("PASS "+n+" production source-policy cases");}
}
