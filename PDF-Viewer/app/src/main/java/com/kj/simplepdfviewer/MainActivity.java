package com.kj.simplepdfviewer;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.*;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.view.*;
import android.widget.*;
import android.os.ParcelFileDescriptor;
import java.io.*;

public class MainActivity extends Activity {
  PdfRenderer renderer;
  ParcelFileDescriptor descriptor;
  ImageView image;
  TextView status;
  int page=0;
  float zoom=1f;
  public void onCreate(Bundle b) {
    super.onCreate(b);
    LinearLayout root=new LinearLayout(this); root.setOrientation(1);
    LinearLayout bar=new LinearLayout(this);
    Button open=new Button(this); open.setText("Open PDF"); bar.addView(open);
    Button prev=new Button(this); prev.setText("<"); bar.addView(prev);
    Button next=new Button(this); next.setText(">"); bar.addView(next);
    root.addView(bar);
    status=new TextView(this); status.setText("Choose a PDF to open"); status.setGravity(Gravity.CENTER); root.addView(status);
    ScrollView scroll=new ScrollView(this);
    image=new ImageView(this); image.setAdjustViewBounds(true); image.setScaleType(ImageView.ScaleType.FIT_CENTER); scroll.addView(image);
    root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1)); setContentView(root);
    open.setOnClickListener(v->{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT); i.setType("application/pdf"); i.addCategory(Intent.CATEGORY_OPENABLE); startActivityForResult(i,1);});
    prev.setOnClickListener(v->{if(renderer!=null && page>0){page--;render();}});
    next.setOnClickListener(v->{if(renderer!=null && page+1<renderer.getPageCount()){page++;render();}});
    image.setOnTouchListener(new View.OnTouchListener(){
      android.view.ScaleGestureDetector detector=new android.view.ScaleGestureDetector(MainActivity.this,new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener(){
        public boolean onScale(android.view.ScaleGestureDetector d){zoom=Math.max(0.5f,Math.min(3f,zoom*d.getScaleFactor()));image.setScaleX(zoom);image.setScaleY(zoom);return true;}
      });
      public boolean onTouch(View v,android.view.MotionEvent e){detector.onTouchEvent(e);return true;}
    });
    if(getIntent()!=null && getIntent().getData()!=null)load(getIntent().getData());
  }
  protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==1 && result==RESULT_OK && data!=null)load(data.getData());}
  void load(Uri uri){try{
    if(renderer!=null)renderer.close();if(descriptor!=null)descriptor.close();
    descriptor=getContentResolver().openFileDescriptor(uri,"r");renderer=new PdfRenderer(descriptor);page=0;zoom=1f;image.setScaleX(1);image.setScaleY(1);render();
  }catch(Exception e){status.setText("Cannot open PDF: "+e.getMessage());}}
  void render(){try(PdfRenderer.Page p=renderer.openPage(page)){
    float scale=Math.min(2f,1600f/Math.max(1,p.getWidth()));
    Bitmap bmp=Bitmap.createBitmap(Math.max(1,(int)(p.getWidth()*scale)),Math.max(1,(int)(p.getHeight()*scale)),Bitmap.Config.ARGB_8888);
    bmp.eraseColor(Color.WHITE);p.render(bmp,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);image.setImageBitmap(bmp);
    status.setText("Page "+(page+1)+" of "+renderer.getPageCount());
  }catch(Exception e){status.setText("Render error: "+e.getMessage());}}
  protected void onDestroy(){super.onDestroy();try{if(renderer!=null)renderer.close();if(descriptor!=null)descriptor.close();}catch(Exception ignored){}}
}
