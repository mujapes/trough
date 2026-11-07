package com.trough.hot;
/** Reload restoration must use the saved page and offset before the first layout. */
final class TraceViewport {
 static int anchor(boolean pageChanged,boolean restoring,boolean follow,int saved,int current){return pageChanged?0:restoring&&!follow?Math.max(0,saved):Math.max(0,current);}
 static int pageEnd(int saved,int total,boolean follow){return follow||saved<0?total:Math.min(total,Math.max(0,saved));}
}
