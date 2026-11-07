package com.trough.hot;
public class TraceViewportTest {
 static void eq(int expected,int actual){if(expected!=actual)throw new AssertionError(expected+" != "+actual);}
 public static void main(String[] args){
 eq(1342,TraceViewport.anchor(false,true,false,1342,0));
 eq(42000,TraceViewport.pageEnd(42000,42500,false));
 eq(1342,TraceViewport.anchor(false,false,false,1342,1342));
 eq(0,TraceViewport.anchor(true,true,false,1342,0));
 eq(42500,TraceViewport.pageEnd(42000,42500,true));
 eq(42500,TraceViewport.pageEnd(-1,42500,false));
 eq(42000,TraceViewport.pageEnd(42500,42000,false));
 eq(0,TraceViewport.anchor(false,true,true,1342,0));
 System.out.println("PASS: 8 viewport reload/page/follow cases");
 }
}
