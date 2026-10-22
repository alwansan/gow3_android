package com.gow3.android.game;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.Typeface;
import android.util.SparseArray;
import android.view.MotionEvent;
import android.view.View;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import com.gow3.android.core.settings.GamePrefs;

/** Translucent PlayStation-style controls with a persistent, draggable layout. */
final class TouchControllerOverlay extends View {
    private static final String[] NAMES={
        "l2","l1","touchpad","options","r1","r2",
        "up","left","right","down","triangle","circle","cross","square",
        "leftstick","l3","r3","rightstick"
    };
    private static final float[][] DEFAULT={
        {87,105},{213,105},{640,105},{773,105},{1067,105},{1193,105},
        {160,395},{90,465},{230,465},{160,535},
        {1120,405},{1190,475},{1120,545},{1050,475},
        {320,612},{420,614},{860,614},{960,612}
    };
    private final PadBridge pad;
    private final GamePrefs prefs;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Map<String,PointF> centers=new LinkedHashMap<>();
    private final Map<String,Float> sizes=new LinkedHashMap<>();
    private final SparseArray<String> controls=new SparseArray<>();
    private final SparseArray<PointF> positions=new SparseArray<>();
    private final Set<String> pressed=new LinkedHashSet<>();
    private boolean editing;
    // Editor: the finger dragging `dragged` (moves and selects it), and the bar's −/+ resizing
    // of `selected`（参考 Bloodborne-Android：按钮步进缩放，不用双指捏合）。
    private int editPointer=-1;
    private String dragged, selected;
    private Runnable onEditDone;

    /** 布局持久化由 GameActivity 经容器注入（design D4），本控件不自建实例。 */
    TouchControllerOverlay(Context context,PadBridge pad,GamePrefs prefs) {
        super(context); this.pad=pad; this.prefs=prefs;
        for (int i=0;i<NAMES.length;i++) {
            String name=NAMES[i];
            centers.put(name,new PointF(prefs.overlayX(name,DEFAULT[i][0]),
                                        prefs.overlayY(name,DEFAULT[i][1])));
            sizes.put(name,prefs.overlaySize(name,1f));
        }
        setContentDescription("On-screen PlayStation controller");
    }
    private PointF center(String name) { return centers.get(name); }
    /** A control's size: a factor of its default geometry（参考 Bloodborne-Android 同款边界与语义）。 */
    private static final float MIN_SIZE=0.6f, MAX_SIZE=2f, SIZE_STEP=1.1f;
    private float size(String name) { Float s=sizes.get(name); return s==null ? 1f : s; }
    private static float clampSize(float s) { return Math.max(MIN_SIZE,Math.min(MAX_SIZE,s)); }
    /** The d-pad's size: the mean of its four buttons'. */
    private float dpadSize() { return (size("up")+size("left")+size("right")+size("down"))/4f; }
    /** 步进缩放选中控件并立即持久化（−/+ 按钮调用）。 */
    private void resize(String name,float factor) {
        sizes.put(name,clampSize(size(name)*factor));
        prefs.setOverlaySize(name,size(name));
        invalidate();
    }

    // The editor's bar at the top: RESET (asks first), − (smaller), DONE, + (larger).
    private static final float BAR_TOP=9, BAR_BOTTOM=62;
    private static final float[] RESET={358,458}, MINUS={468,538}, DONE={548,732}, PLUS={742,812};
    private static boolean in(float[] box,float x,float y) { return x>box[0] && x<box[1] && y<BAR_BOTTOM+10; }
    private void confirmReset() {
        new android.app.AlertDialog.Builder(getContext()).setTitle("Reset controller layout?")
            .setMessage("All buttons go back to their default places and sizes.")
            .setNegativeButton("Cancel",null)
            .setPositiveButton("Reset",(d,w) -> { resetLayout(); selected=null; invalidate(); }).show();
    }
    // The 1280x720 layout drawn at one scale in the centred 16:9 area, as the game (MainActivity):
    // round on any screen shape. Controls may be dragged into the bars around it.
    private float scale() { return Math.max(1e-3f,Math.min(getWidth()/1280f,getHeight()/720f)); }
    private float offsetX() { return (getWidth()-1280f*scale())/2f; }
    private float offsetY() { return (getHeight()-720f*scale())/2f; }
    private float layoutX(MotionEvent e,int index) { return (e.getX(index)-offsetX())/scale(); }
    private float layoutY(MotionEvent e,int index) { return (e.getY(index)-offsetY())/scale(); }
    private static float distance(float x,float y,PointF p) { return (float)Math.hypot(x-p.x,y-p.y); }
    private static boolean stick(String name) { return name.equals("leftstick") || name.equals("rightstick"); }
    private static boolean rectangle(String name) {
        return name.equals("l1") || name.equals("l2") || name.equals("r1") ||
               name.equals("r2") || name.equals("touchpad") || name.equals("options");
    }
    private PointF dpadCenter() {
        PointF u=center("up"),l=center("left"),r=center("right"),d=center("down");
        return new PointF((u.x+l.x+r.x+d.x)/4f,(u.y+l.y+r.y+d.y)/4f);
    }
    private String hit(float x,float y) {
        PointF dpad=dpadCenter(); float k=dpadSize();
        if (!editing && distance(x,y,dpad)<112*k && distance(center("up").x,center("up").y,dpad)<115*k &&
            distance(center("left").x,center("left").y,dpad)<115*k &&
            distance(center("right").x,center("right").y,dpad)<115*k &&
            distance(center("down").x,center("down").y,dpad)<115*k) return "dpad";
        String winner=null; float best=Float.MAX_VALUE;
        for (String name:NAMES) {
            PointF p=center(name); float s=size(name);
            float dx=Math.abs(x-p.x),dy=Math.abs(y-p.y);
            boolean inside=rectangle(name) ? dx<(name.equals("touchpad") ? 72 : 62)*s && dy<38*s
                    : distance(x,y,p)<(stick(name) ? 70 : name.equals("l3") || name.equals("r3") ? 39 : 48)*s;
            if (inside && distance(x,y,p)<best) { best=distance(x,y,p); winner=name; }
        }
        return winner;
    }
    private static int axis(float v) { return Math.max(0,Math.min(255,Math.round((v+1f)*127.5f))); }

    private void publish() {
        pressed.clear();
        int lx=128,ly=128,rx=128,ry=128,l2=0,r2=0;
        boolean left=false,right=false;
        for (int i=0;i<controls.size();i++) {
            String control=controls.valueAt(i);
            PointF p=positions.get(controls.keyAt(i));
            if (p==null) continue;
            if (control.equals("dpad")) {
                PointF c=dpadCenter(); float t=22*dpadSize();
                if (p.x<c.x-t) pressed.add("left");
                if (p.x>c.x+t) pressed.add("right");
                if (p.y<c.y-t) pressed.add("up");
                if (p.y>c.y+t) pressed.add("down");
            } else if (control.equals("leftstick")) {
                left=true; PointF c=center(control); float range=56*size(control);
                lx=axis(Math.max(-1f,Math.min(1f,(p.x-c.x)/range)));
                ly=axis(Math.max(-1f,Math.min(1f,(p.y-c.y)/range)));
            } else if (control.equals("rightstick")) {
                right=true; PointF c=center(control); float range=56*size(control);
                rx=axis(Math.max(-1f,Math.min(1f,(p.x-c.x)/range)));
                ry=axis(Math.max(-1f,Math.min(1f,(p.y-c.y)/range)));
            } else if (control.equals("l2")) { l2=255; pressed.add(control); }
            else if (control.equals("r2")) { r2=255; pressed.add(control); }
            else if (control.equals("touchpad")) pressed.add("touchpad_right"); /* 右侧点击:打开游戏暂停菜单,进而可进存档列表 */
            else pressed.add(control);
        }
        pad.virtual(pressed,lx,ly,rx,ry,left,right,l2,r2);
        invalidate();
    }
    /** 开关屏幕控制器并同步可见性；与同目录 PerfHudOverlay.setActive() 同形，启动恢复与抽屉
     *  「On-screen controller」行都走这里。可见性归该开关行所有，抽屉开合不参与：scrim 已全屏
     *  覆盖并拦截触摸（spec「抽屉打开期间由全屏遮罩覆盖并拦截触摸」）。只切可见性，不触碰
     *  按键、布局与编辑状态。 */
    void setActive(boolean on) { setVisibility(on ? VISIBLE : GONE); }
    void release() { controls.clear(); positions.clear(); publish(); }
    void edit(Runnable done) { release(); editing=true; dragged=null; selected=null; onEditDone=done; invalidate(); }
    private void finishEdit() {
        editPointer=-1; dragged=null; selected=null; editing=false; invalidate();
        if (onEditDone!=null) onEditDone.run();
        onEditDone=null;
    }
    void resetLayout() {
        prefs.clearOverlayLayout();
        for (int i=0;i<NAMES.length;i++) {
            centers.get(NAMES[i]).set(DEFAULT[i][0],DEFAULT[i][1]);
            sizes.put(NAMES[i],1f);
        }
        release(); invalidate();
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        int action=e.getActionMasked(),at=e.getActionIndex(),id=e.getPointerId(at);
        if (editing) {
            if (action==MotionEvent.ACTION_CANCEL) { editPointer=-1; dragged=null; return true; }
            if (action==MotionEvent.ACTION_DOWN) {
                float x=layoutX(e,at),y=layoutY(e,at);
                if (in(DONE,x,y)) { finishEdit(); return true; }
                if (in(RESET,x,y)) { confirmReset(); return true; }
                if (in(MINUS,x,y) || in(PLUS,x,y)) {
                    if (selected!=null) resize(selected,in(PLUS,x,y) ? SIZE_STEP : 1f/SIZE_STEP);
                    return true;
                }
                dragged=hit(x,y); editPointer=dragged==null ? -1 : id;
                if (dragged!=null) selected=dragged;
                invalidate();
            } else if (action==MotionEvent.ACTION_MOVE && editPointer>=0 && dragged!=null) {
                int index=e.findPointerIndex(editPointer);
                float barX=offsetX()/scale(),barY=offsetY()/scale();
                if (index>=0) center(dragged).set(
                    Math.max(36-barX,Math.min(1244+barX,layoutX(e,index))),
                    Math.max(36-barY,Math.min(684+barY,layoutY(e,index))));
                invalidate();
        } else if ((action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_POINTER_UP)
                   && id==editPointer && dragged!=null) {
            PointF p=center(dragged);
            prefs.setOverlayPosition(dragged,p.x,p.y);
            prefs.setOverlaySize(dragged,size(dragged));
            editPointer=-1; dragged=null;
        }
            return true;
        }
        if (action==MotionEvent.ACTION_CANCEL) { release(); return true; }
        if (action==MotionEvent.ACTION_DOWN || action==MotionEvent.ACTION_POINTER_DOWN) {
            float x=layoutX(e,at),y=layoutY(e,at);
            // A touch that misses every control is dropped: there is no pointer channel under
            // Bionic (no X11, no XTEST), so it could not reach the game anyway. The right stick is
            // anchored where the finger landed instead. This is what Bloodborne-Android does too.
            String name=hit(x,y);
            if (name==null) return true;
            controls.put(id,name);
            positions.put(id,new PointF(x,y));
        } else if (action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_POINTER_UP) {
            controls.remove(id); positions.remove(id);
        }
        if (action==MotionEvent.ACTION_MOVE) {
            for (int i=0;i<e.getPointerCount();i++) {
                PointF p=positions.get(e.getPointerId(i));
                if (p!=null) p.set(layoutX(e,i),layoutY(e,i));
            }
        }
        publish(); return true;
    }

    private void circle(Canvas c,String name,float r,String label,int accent) {
        PointF p=center(name); float s=size(name); r*=s;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(name.equals(dragged) ? 0xffe8dfc9 : pressed.contains(name) ? 0xaae8dfc9 : 0x6630394c);
        c.drawCircle(p.x,p.y,r,paint);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(3); paint.setColor(accent);
        c.drawCircle(p.x,p.y,r,paint);
        paint.setStyle(Paint.Style.FILL); paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextSize((label.length()>2 ? 19 : 33)*s); paint.setTextAlign(Paint.Align.CENTER);
        c.drawText(label,p.x,p.y-(paint.ascent()+paint.descent())/2,paint);
    }
    private void rectangle(Canvas c,String name,float width,String label) {
        PointF p=center(name); float s=size(name); width*=s;
        float half=width/2,halfH=25*s,radius=14*s;
        // The touchpad button presses "touchpad_right" (right-side click, opens the pause menu),
        // not "touchpad", so highlight it from that signal too.
        boolean lit=pressed.contains(name) ||
            (name.equals("touchpad") && pressed.contains("touchpad_right"));
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(name.equals(dragged) ? 0xffe8dfc9 : lit ? 0xaae8dfc9 : 0x6630394c);
        c.drawRoundRect(p.x-half,p.y-halfH,p.x+half,p.y+halfH,radius,radius,paint);
        paint.setStyle(Paint.Style.STROKE); paint.setColor(0xbbc6ad76); paint.setStrokeWidth(2);
        c.drawRoundRect(p.x-half,p.y-halfH,p.x+half,p.y+halfH,radius,radius,paint);
        paint.setStyle(Paint.Style.FILL); paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextSize(19*s); paint.setTextAlign(Paint.Align.CENTER);
        c.drawText(label,p.x,p.y-(paint.ascent()+paint.descent())/2,paint);
    }
    private void drawStick(Canvas c,String name) {
        PointF center=center(name); float s=size(name);
        paint.setStyle(Paint.Style.FILL); paint.setColor(name.equals(dragged) ? 0xffe8dfc9 : 0x6630394c);
        c.drawCircle(center.x,center.y,57*s,paint);
        paint.setStyle(Paint.Style.STROKE); paint.setColor(0xffa0aec0); paint.setStrokeWidth(3);
        c.drawCircle(center.x,center.y,57*s,paint);
        float knobX=center.x,knobY=center.y,travel=27*s;
        for (int i=0;i<controls.size();i++) {
            if (name.equals(controls.valueAt(i))) {
                PointF p=positions.get(controls.keyAt(i));
                if (p!=null) {
                    knobX=center.x+Math.max(-travel,Math.min(travel,p.x-center.x));
                    knobY=center.y+Math.max(-travel,Math.min(travel,p.y-center.y));
                }
                break;
            }
        }
        paint.setStyle(Paint.Style.FILL); paint.setColor(0xbba0aec0);
        c.drawCircle(knobX,knobY,28*s,paint);
        paint.setColor(0xff15202c); paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextAlign(Paint.Align.CENTER); paint.setTextSize(22*s);
        c.drawText(name.equals("leftstick") ? "L" : "R",knobX,
                   knobY-(paint.ascent()+paint.descent())/2,paint);
    }
    @Override protected void onDraw(Canvas c) {
        super.onDraw(c); c.save(); c.translate(offsetX(),offsetY()); c.scale(scale(),scale());
        rectangle(c,"l2",108,"L2"); rectangle(c,"l1",108,"L1");
        rectangle(c,"r1",108,"R1"); rectangle(c,"r2",108,"R2");
        rectangle(c,"touchpad",126,"TOUCH"); rectangle(c,"options",108,"OPTIONS");
        circle(c,"up",39,"▲",0xffc6ad76); circle(c,"left",39,"◀",0xffc6ad76);
        circle(c,"right",39,"▶",0xffc6ad76); circle(c,"down",39,"▼",0xffc6ad76);
        circle(c,"triangle",39,"△",0xff83cdaa); circle(c,"circle",39,"○",0xffe38288);
        circle(c,"cross",39,"×",0xff88aaff); circle(c,"square",39,"□",0xffd6a0ce);
        drawStick(c,"leftstick"); drawStick(c,"rightstick");
        circle(c,"l3",29,"L3",0xffa0aec0); circle(c,"r3",29,"R3",0xffa0aec0);
        if (editing) {
            barButton(c,RESET,"RESET",true); barButton(c,MINUS,"−",selected!=null);
            barButton(c,DONE,"DONE",true); barButton(c,PLUS,"+",selected!=null);
            paint.setStyle(Paint.Style.FILL); paint.setTextSize(17); paint.setColor(0xffe8dfc9);
            paint.setTextAlign(Paint.Align.CENTER);
            c.drawText(selected==null ? "Drag a button to move and select it" :
                       "Drag to move · −/+ to resize ("+NAMES.length+" buttons, tap DONE when finished)",
                       640,BAR_BOTTOM+110,paint);
        }
        c.restore();
    }
    /** 编辑条按钮：enabled=false 时描边置灰（−/+ 未选中控件时不可用）。 */
    private void barButton(Canvas c,float[] box,String label,boolean enabled) {
        paint.setStyle(Paint.Style.FILL); paint.setColor(0xcc15171d);
        c.drawRoundRect(box[0],BAR_TOP,box[1],BAR_BOTTOM,13,13,paint);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2);
        paint.setColor(enabled ? 0xffc6ad76 : 0x66c6ad76);
        c.drawRoundRect(box[0],BAR_TOP,box[1],BAR_BOTTOM,13,13,paint);
        paint.setStyle(Paint.Style.FILL); paint.setTextSize(label.length()>1 ? 20 : 30);
        paint.setTextAlign(Paint.Align.CENTER);
        c.drawText(label,(box[0]+box[1])/2,(BAR_TOP+BAR_BOTTOM)/2-(paint.ascent()+paint.descent())/2,paint);
    }
}
