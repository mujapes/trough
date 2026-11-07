package com.trough.hot;
import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
/** Give keyboard measurement the current viewport, not the previous layout's height. */
final class KeyboardDock extends LinearLayout {
 private final ForgeKeyboard keyboard;
 KeyboardDock(Context context,ForgeKeyboard keyboard){super(context);this.keyboard=keyboard;setOrientation(VERTICAL);}
 @Override protected void onMeasure(int widthSpec,int heightSpec){
  if(View.MeasureSpec.getMode(heightSpec)!=View.MeasureSpec.UNSPECIFIED)
   keyboard.adaptHeightForMeasure(View.MeasureSpec.getSize(heightSpec));
  super.onMeasure(widthSpec,heightSpec);
 }
}
