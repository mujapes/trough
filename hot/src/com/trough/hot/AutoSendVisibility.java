package com.trough.hot;
/** Eyes-free input may hide its editor, but never permits sending from a hidden app. */
final class AutoSendVisibility {
 static boolean allows(boolean editorShown,boolean eyesFree,boolean rootShown,boolean keyboardShown,boolean windowVisible){
  return windowVisible && (editorShown || eyesFree && rootShown && keyboardShown);
 }
}
