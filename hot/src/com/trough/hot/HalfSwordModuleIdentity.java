// Steel-tinted weapon material; geometry and solver remain unchanged.
package com.trough.hot;
// Module selection is part of the Java source/build identity. Change together
// with current.json; a source-key change creates a fresh JNI-owning classloader.
final class HalfSwordModuleIdentity {
 static final String SHA256="4b8e29c17e40e85619c1a535cac8f705fe5ee394af5fd958dd0460f2cfe5a2c5";
 static void require(String hash)throws java.io.IOException {
  if(!SHA256.equals(hash))throw new java.io.IOException("Native module selection requires matching UI build");
 }
}
