package com.trough.hot;
/** Exact immutable source identity; equal hashes or equal kinds alone cannot reuse a row. */
final class TranscriptRowIdentity {
 static boolean matches(Object saved,String kind,String body){
  if(!(saved instanceof String[]))return false;
  String[] row=(String[])saved;
  return row.length==2&&kind!=null&&kind.equals(row[0])&&(body==null?row[1]==null:body.equals(row[1]));
 }
}
