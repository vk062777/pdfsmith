package android.graphics;
public class Matrix {
  private final float[] v=new float[]{1,0,0,0,1,0,0,0,1};
  public Matrix(){}
  public void reset(){ v[0]=1;v[1]=0;v[2]=0;v[3]=0;v[4]=1;v[5]=0;v[6]=0;v[7]=0;v[8]=1; }
  public void set(Matrix m){ System.arraycopy(m.v,0,v,0,9); }
  public void getValues(float[] a){ System.arraycopy(v,0,a,0,9); }
  public void setValues(float[] a){ System.arraycopy(a,0,v,0,9); }
  public void postTranslate(float dx,float dy){} public void preTranslate(float dx,float dy){}
  public void postScale(float sx,float sy){} public void preScale(float sx,float sy){}
  public void postRotate(float d){} public void preRotate(float d){}
  public void postConcat(Matrix m){} public void preConcat(Matrix m){}
  public void setConcat(Matrix a,Matrix b){}
  public void mapPoints(float[] p){} public void mapPoints(float[] p,float[] d){ if(d!=null&&p!=null) System.arraycopy(p,0,d,0,Math.min(p.length,d.length)); }
  public boolean mapRect(RectF r){ return true; }
  public boolean mapRect(RectF d,RectF s){ if(d!=null&&s!=null) d.set(s.left,s.top,s.right,s.bottom); return true; }
  public boolean invert(Matrix inv){ return true; }
}
