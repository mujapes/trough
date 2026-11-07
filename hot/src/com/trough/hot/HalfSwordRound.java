package com.trough.hot;
/** Prototype round result, independent of physical knockouts or project work state. */
final class HalfSwordRound {
 static int result(int player,int opponent){return player<=0?(opponent<=0?3:2):(opponent<=0?1:0);}
 static String label(int result){return result==1?"Round won · Reset to play again":result==2?"Round lost · Reset to play again":result==3?"Round drawn · Reset to play again":"";}
}
