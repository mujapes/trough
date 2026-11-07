package com.trough.hot;

import java.io.*;
import java.util.*;

/** Reads JSON-RPC without materializing replayed conversation history. */
final class CodexStream implements Closeable {
    private final Reader in;
    private int c=-2, nodes, chars;
    static final int MAX_STRING=65536, MAX_CHARS=2097152, MAX_NODES=32768;
    CodexStream(InputStream input) { in=new BufferedReader(new InputStreamReader(input,java.nio.charset.StandardCharsets.UTF_8),16384); }
    private int peek() throws IOException { if(c==-2)c=in.read(); return c; }
    private int take() throws IOException { int v=peek();c=-2;return v; }
    private void space() throws IOException { while(peek()==' '||peek()=='\t'||peek()=='\r')take(); }
    Object next() throws IOException {
        space();while(peek()=='\n'){take();space();}
        if(peek()==-1)return null;
        nodes=0;chars=0;
        Object value=value(0,"");space();
        if(peek()!=-1 && take()!='\n')throw new IOException("Invalid Codex frame ending");
        return value;
    }
    private IOException bad(){return new IOException("Invalid or excessive Codex JSON response");}
    private Object value(int depth,String path) throws IOException {
        if(depth>96 || ++nodes>MAX_NODES)throw bad();
        space();int ch=peek();
        if(ch=='"')return string();
        if(ch=='{') {
            take();Map<String,Object> out=new LinkedHashMap<>();space();
            if(peek()=='}'){take();return out;}
            while(true){
                space();if(peek()!='"')throw bad();String key=string();space();if(take()!=':')throw bad();space();
                // Trough restores its own transcript and consumes only thread metadata.
                // Discard history only at the protocol's thread metadata paths.
                if("turns".equals(key) && ("result.thread".equals(path)||"params.thread".equals(path)) && peek()=='[') {
                    skipArray();out.put(key,Collections.emptyList());
                } else out.put(key,value(depth+1,path.isEmpty()?key:path+"."+key));
                space();int end=take();if(end=='}')return out;if(end!=',')throw bad();
            }
        }
        if(ch=='['){
            take();List<Object> out=new ArrayList<>();space();if(peek()==']'){take();return out;}
            while(true){out.add(value(depth+1,path+"[]"));space();int end=take();if(end==']')return out;if(end!=',')throw bad();}
        }
        StringBuilder token=new StringBuilder();
        while((ch=peek())!=-1 && ch!=','&&ch!=']'&&ch!='}'&&ch!=' '&&ch!='\t'&&ch!='\r'&&ch!='\n'){
            if(token.length()>100)throw bad();token.append((char)take());
        }
        String t=token.toString();
        if("null".equals(t))return null;if("true".equals(t))return true;if("false".equals(t))return false;
        try{if(t.indexOf('.')>=0||t.indexOf('e')>=0||t.indexOf('E')>=0)return Double.valueOf(t);return Long.valueOf(t);}catch(NumberFormatException e){throw bad();}
    }
    private String string() throws IOException {
        if(take()!='"')throw bad();StringBuilder out=new StringBuilder();boolean truncated=false;
        while(true){
            int ch=take();if(ch==-1||ch=='\n'||ch=='\r')throw bad();if(ch=='"')break;
            if(ch=='\\'){
                ch=take();
                switch(ch){
                    case '"':case '\\':case '/':break;
                    case 'b':ch='\b';break;case 'f':ch='\f';break;case 'n':ch='\n';break;case 'r':ch='\r';break;case 't':ch='\t';break;
                    case 'u':ch=0;for(int i=0;i<4;i++){int h=Character.digit((char)take(),16);if(h<0)throw bad();ch=(ch<<4)|h;}break;
                    default:throw bad();
                }
            } else if(ch<32)throw bad();
            if(out.length()<MAX_STRING){if(++chars>MAX_CHARS)throw bad();out.append((char)ch);}else truncated=true;
        }
        if(truncated)out.append("\n[Output truncated in Trough; full output remains in the Codex session]");
        return out.toString();
    }
    private void skipArray() throws IOException {
        int depth=0;boolean quoted=false,escaped=false;
        do{
            int ch=take();if(ch==-1||ch=='\n'||ch=='\r')throw bad();
            if(quoted){if(escaped)escaped=false;else if(ch=='\\')escaped=true;else if(ch=='"')quoted=false;}
            else if(ch=='"')quoted=true;
            else if(ch=='['||ch=='{'){if(++depth>128)throw bad();}
            else if(ch==']'||ch=='}')depth--;
        }while(depth>0);
    }
    public void close() throws IOException {in.close();}
}
