package com.qozix.tileview.tiles;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.View;

import com.qozix.tileview.TileView;
import com.qozix.tileview.detail.DetailLevel;
import com.qozix.tileview.graphics.BitmapProvider;
import com.qozix.tileview.graphics.BitmapProviderAssets;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

public class TileCanvasViewGroup extends View {

  private static final int RENDER_FLAG = 1;

  public static final int DEFAULT_RENDER_BUFFER = 250;
  public static final int FAST_RENDER_BUFFER = 15;

  private static final int DEFAULT_TRANSITION_DURATION = 200;

  private float mScale = 1;

  private BitmapProvider mBitmapProvider;
  private HashMap<Float, TileCanvasView> mTileCanvasViewHashMap = new HashMap<>();

  private DetailLevel mDetailLevelToRender;
  private DetailLevel mLastRequestedDetailLevel;
  private DetailLevel mLastRenderedDetailLevel;

  private Rect mDrawingRect = new Rect();

  private boolean mRenderIsCancelled = false;
  private boolean mRenderIsSuppressed = false;
  private boolean mIsRendering = false;

  private boolean mShouldRecycleBitmaps = true;

  private boolean mTransitionsEnabled = true;
  private int mTransitionDuration = DEFAULT_TRANSITION_DURATION;

  private TileRenderThrottleHandler mTileRenderThrottleHandler;
  private TileRenderListener mTileRenderListener;

  private int mRenderBuffer = DEFAULT_RENDER_BUFFER;

  private TileRenderPoolExecutor mTileRenderPoolExecutor;

  private Set<Tile> mTilesInCurrentViewport = new HashSet<>();
  private Set<Tile> mTilesNotInCurrentViewport = new HashSet<>();
  private Set<Tile> mTilesAlreadyRendered = new HashSet<>();
  private Set<Tile> mTilesToBeRendered = new HashSet<>();
  private Set<Tile> mTilesDecodedAndReadyToDraw = new HashSet<>();
  private Set<Tile> mTilesDecodedAndWithinInvalidRegion = new HashSet<>();

  public TileCanvasViewGroup( Context context ) {
    super( context );
    setWillNotDraw( false );
    mTileRenderThrottleHandler = new TileRenderThrottleHandler( this );
    mTileRenderPoolExecutor = new TileRenderPoolExecutor();
  }

  public void setScale( float factor ) {
    mScale = factor;
    invalidate();
  }

  public float getScale() {
    return mScale;
  }

  public boolean getTransitionsEnabled() {
    return mTransitionsEnabled;
  }

  public void setTransitionsEnabled( boolean enabled ) {
    mTransitionsEnabled = enabled;
  }

  public int getTransitionDuration() {
    return mTransitionDuration;
  }

  public void setTransitionDuration( int duration ) {
    mTransitionDuration = duration;
  }

  public BitmapProvider getBitmapProvider(){
    if( mBitmapProvider == null ) {
      mBitmapProvider = new BitmapProviderAssets();
    }
    return mBitmapProvider;
  }

  public void setBitmapProvider( BitmapProvider bitmapProvider ) {
    mBitmapProvider = bitmapProvider;
  }

  public void setTileRenderListener( TileRenderListener tileRenderListener ) {
    mTileRenderListener = tileRenderListener;
  }

  public int getRenderBuffer() {
    return mRenderBuffer;
  }

  public void setRenderBuffer( int renderBuffer ) {
    mRenderBuffer = renderBuffer;
  }

  public boolean getShouldRecycleBitmaps() {
    return mShouldRecycleBitmaps;
  }

  public void setShouldRecycleBitmaps( boolean shouldRecycleBitmaps ) {
    mShouldRecycleBitmaps = shouldRecycleBitmaps;
  }


  /**
   * The layout dimensions supplied to this ViewGroup will be exactly as large as the scaled
   * width and height of the containing ZoomPanLayout (or TileView).  However, when the canvas
   * is scaled, it's clip area is also scaled - offset this by providing dimensions scaled as
   * large as the smallest size the TileCanvasView might be.
   */

  public void requestRender() {
    mRenderIsCancelled = false;
    mRenderIsSuppressed = false;
    if( mDetailLevelToRender == null ) {
      return;
    }
    if( !mTileRenderThrottleHandler.hasMessages( RENDER_FLAG ) ) {
      mTileRenderThrottleHandler.sendEmptyMessageDelayed( RENDER_FLAG, mRenderBuffer );
    }
  }

  /**
   * Prevent new render tasks from starting, attempts to interrupt ongoing tasks, and will
   * prevent queued tiles from begin decoded or rendered.
   */
  public void cancelRender() {
    mRenderIsCancelled = true;
    if( mTileRenderPoolExecutor != null ){
      mTileRenderPoolExecutor.cancel();
    }
  }

  /**
   * Prevent new render tasks from starting, but does not cancel any ongoing operations.
   */
  public void suppressRender() {
    mRenderIsSuppressed = true;
  }

  /**
   * Draw tile bitmaps into the surface canvas displayed by this View.
   * @param canvas The Canvas instance to draw tile bitmaps into.
   */
  private void drawTiles( Canvas canvas ) {
    for( Tile tile : mTilesDecodedAndReadyToDraw ) {
      if( mDrawingRect.contains( tile.getScaledRect() )) {  // TODO: pass this to Tile.draw?
        tile.draw( canvas );
        mTilesAlreadyRendered.add( tile );
      }
    }
    mTilesDecodedAndReadyToDraw.removeAll( mTilesAlreadyRendered );
  }

  public void updateTileSet( DetailLevel detailLevel ) {  // TODO: need this?
    mDetailLevelToRender = detailLevel;
    if( mDetailLevelToRender == null ) {
      return;
    }
    if( mDetailLevelToRender.equals( mLastRequestedDetailLevel ) ) {
      return;
    }
    mLastRequestedDetailLevel = mDetailLevelToRender;
    cancelRender();
    requestRender();
  }

  public boolean getIsRendering() {
    return mIsRendering;
  }

  public void clear() {
    suppressRender();
    cancelRender();
    mTilesInCurrentViewport.clear();
    invalidate();
  }

  void renderTiles() {
    if( !mRenderIsCancelled && !mRenderIsSuppressed && mDetailLevelToRender != null ) {
      beginRenderTask();
    }
  }

  private void beginRenderTask() {

    // do we need to bother?  if visible columns and rows are same as previously computed, fast-fail
    boolean changed = mDetailLevelToRender.computeCurrentState();  // TODO: maintain compare state here instead?
    if( !changed && mDetailLevelToRender.equals( mLastRenderedDetailLevel ) ) {
      return;
    }

    // these tiles are mathematically within the current viewport
    Set<Tile> recentlyComputedVisibleTileSet = mDetailLevelToRender.getVisibleTilesFromLastViewportComputation();

    // this block updates the "current" tile set to show what is and is not in viewport with most recent computation
    for( Tile tile : mTilesInCurrentViewport ) {
      if( !recentlyComputedVisibleTileSet.contains( tile ) ) {
        mTilesNotInCurrentViewport.add( tile );
      }
    }
    mTilesInCurrentViewport.addAll( recentlyComputedVisibleTileSet );
    mTilesInCurrentViewport.removeAll( mTilesNotInCurrentViewport );

    // now destroy everything not in the current viewport
    mTilesAlreadyRendered.removeAll( mTilesNotInCurrentViewport );
    mTilesDecodedAndReadyToDraw.removeAll( mTilesNotInCurrentViewport );  // necessary?
    for( Tile tile : mTilesNotInCurrentViewport ) {
      tile.destroy( mShouldRecycleBitmaps );
    }
    mTilesNotInCurrentViewport.clear();

    // all tile mathematically in viewport, less those already rendered, get sent to the render task
    mTilesToBeRendered.clear();
    mTilesToBeRendered.addAll( mTilesInCurrentViewport );
    mTilesToBeRendered.removeAll( mTilesAlreadyRendered );

    if( mTileRenderPoolExecutor != null ){
      mTileRenderPoolExecutor.queue( this, mTilesToBeRendered );
    }
  }

  // this tile has been decoded by the time it gets passed here
  void addTileToCanvas(final Tile tile ) {
    if( !mTilesInCurrentViewport.contains( tile ) ) {
      return;
    }
    if( mTilesAlreadyRendered.contains( tile )) {
      return;
    }
    boolean wasAdded = mTilesDecodedAndReadyToDraw.add( tile );
    if(!wasAdded){
      return;
    }
    tile.setTransitionsEnabled( mTransitionsEnabled );
    tile.setTransitionDuration( mTransitionDuration );
    tile.stampTime();
    invalidate();
    //invalidate( tile.getScaledRect() );
    Log.d(TileView.class.getSimpleName(), "invalidating " + tile.getScaledRect().toShortString());
  }


  void onRenderTaskPreExecute() {
    mIsRendering = true;
    if( mTileRenderListener != null ) {
      mTileRenderListener.onRenderStart();
    }
  }

  void onRenderTaskCancelled() {
    if( mTileRenderListener != null ) {
      mTileRenderListener.onRenderCancelled();
    }
    mIsRendering = false;
  }

  void onRenderTaskPostExecute() {
    mIsRendering = false;
    mTileRenderThrottleHandler.post( mRenderPostExecuteRunnable );
  }

  void handleTileRenderException( Throwable throwable ) {
    if( throwable instanceof OutOfMemoryError ){
      //cleanup();
    }
  }

  boolean getRenderIsCancelled() {
    return mRenderIsCancelled;
  }

  public void destroy(){
    mTileRenderPoolExecutor.shutdownNow();
    clear();
    for( TileCanvasView tileGroup : mTileCanvasViewHashMap.values() ) {
      tileGroup.clearTiles( mShouldRecycleBitmaps );
    }
    mTileCanvasViewHashMap.clear();
    if( !mTileRenderThrottleHandler.hasMessages( RENDER_FLAG ) ) {
      mTileRenderThrottleHandler.removeMessages( RENDER_FLAG );
    }
  }

  @Override
  public void onDraw( Canvas canvas ) {
    super.onDraw( canvas );
    canvas.getClipBounds( mDrawingRect );
    canvas.save();
    canvas.scale( mScale, mScale );
    drawTiles( canvas );
    canvas.restore();
  }

  private static class TileRenderThrottleHandler extends Handler {

    private final WeakReference<TileCanvasViewGroup> mTileCanvasViewGroupWeakReference;

    public TileRenderThrottleHandler( TileCanvasViewGroup tileCanvasViewGroup ) {
      super( Looper.getMainLooper() );
      mTileCanvasViewGroupWeakReference = new WeakReference<>( tileCanvasViewGroup );
    }

    @Override
    public final void handleMessage( Message message ) {
      final TileCanvasViewGroup tileCanvasViewGroup = mTileCanvasViewGroupWeakReference.get();
      if( tileCanvasViewGroup != null ) {
        tileCanvasViewGroup.renderTiles();
      }
    }
  }

  /**
   * Interface definition for callbacks to be invoked after render operations.
   */
  public interface TileRenderListener {
    void onRenderStart();
    void onRenderCancelled();
    void onRenderComplete();
  }

  // This runnable is required to run on UI thread
  private Runnable mRenderPostExecuteRunnable =  new Runnable() {
    @Override
    public void run() {
      if ( !mTransitionsEnabled ) {
        // we used to cleanup() here, with invalidate
      }
      if( mTileRenderListener != null ) {
        mTileRenderListener.onRenderComplete();
      }
      mLastRenderedDetailLevel = mDetailLevelToRender;
      //invalidate();  // TODO: probably going to be inappropriate here?
      requestRender();
    }
  };
}
