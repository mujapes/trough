package com.trough.hot;
/** Network sources are accepted only through explicit Music stream selection. */
final class MusicSourcePolicy {
 static boolean allowed(String scheme,String host,boolean stream){return stream?"https".equals(scheme)&&host!=null&&!host.isEmpty():"content".equals(scheme)||"file".equals(scheme);}
}
