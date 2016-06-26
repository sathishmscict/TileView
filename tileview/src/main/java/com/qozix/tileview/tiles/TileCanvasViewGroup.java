package com.qozix.tileview.tiles;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.view.View;

import com.qozix.tileview.detail.DetailLevel;
import com.qozix.tileview.graphics.BitmapProvider;
import com.qozix.tileview.graphics.BitmapProviderAssets;

import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

public class TileCanvasViewGroup extends View {

  private static final int RENDER_FLAG = 1;

  public static final int DEFAULT_RENDER_BUFFER = 250;
  public static final int FAST_RENDER_BUFFER = 15;

  private static final int DEFAULT_TRANSITION_DURATION = 200;

  private float mScale = 1;

  private BitmapProvider mBitmapProvider;

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
  private TileRenderThrowableListener mTileRenderThrowableListener;

  private int mRenderBuffer = DEFAULT_RENDER_BUFFER;

  private TileRenderPoolExecutor mTileRenderPoolExecutor;

  private Set<Tile> mTilesInCurrentViewport = new HashSet<>();

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

  public BitmapProvider getBitmapProvider() {
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

  public void setTileRenderThrowableListener( TileRenderThrowableListener tileRenderThrowableListener ) {
    mTileRenderThrowableListener = tileRenderThrowableListener;
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
    if( mTileRenderPoolExecutor != null ) {
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
   *
   * @param canvas The Canvas instance to draw tile bitmaps into.
   */
  private void drawTiles( Canvas canvas ) {
    for( Tile tile : mTilesInCurrentViewport ) {
      if( tile.getState() == Tile.State.DECODED && Rect.intersects( mDrawingRect, tile.getScaledRect() ) ) {  // TODO: pass this to Tile.draw?
        boolean dirty = tile.draw( canvas );
        if( dirty ) {
          invalidate( tile.getScaledRect() );
        }
      }
    }
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

    // if visible columns and rows are same as previously computed, fast-fail
    boolean changed = mDetailLevelToRender.computeCurrentState();  // TODO: maintain compare state here instead?
    if( !changed && mDetailLevelToRender.equals( mLastRenderedDetailLevel ) ) {
      return;
    }

    // determine tiles are mathematically within the current viewport; force re-computation
    mDetailLevelToRender.computeVisibleTilesFromViewport();

    // get rid of anything outside, use previously computed intersections
    cleanup();

    boolean wereTilesAdded = mTilesInCurrentViewport.addAll( mDetailLevelToRender.getVisibleTilesFromLastViewportComputation() );

    if( wereTilesAdded && mTileRenderPoolExecutor != null ) {
      mTileRenderPoolExecutor.queue( this, mTilesInCurrentViewport );
    }

  }

  /**
   * This should seldom be necessary, as it's built into beginRenderTask
   */
  public void cleanup() {
    // these tiles are mathematically within the current viewport, and should be already computed
    Set<Tile> recentlyComputedVisibleTileSet = mDetailLevelToRender.getVisibleTilesFromLastViewportComputation();
    // use an iterator to avoid concurrent modification
    Iterator<Tile> tilesInCurrentViewportIterator = mTilesInCurrentViewport.iterator();
    while( tilesInCurrentViewportIterator.hasNext() ) {
      Tile tile = tilesInCurrentViewportIterator.next();
      // this tile was visible previously, but is no longer, destroy and de-list it
      if( !recentlyComputedVisibleTileSet.contains( tile ) ) {
        tile.destroy( mShouldRecycleBitmaps );
        // an argument could be made to invalidate this rect, but since it's no longer on the heap, lets leave the artifacts and get some benefit from gpu caching
        tilesInCurrentViewportIterator.remove();
      }
    }

  }


  // this tile has been decoded by the time it gets passed here
  void addTileToCanvas( final Tile tile ) {
    if( !mTilesInCurrentViewport.contains( tile ) ) {
      return;
    }
    tile.setTransitionsEnabled( mTransitionsEnabled );
    tile.setTransitionDuration( mTransitionDuration );
    tile.stampTime();
    // this is key!  we're only scheduling the area the new pixels occupy for redraw
    invalidate( tile.getScaledRect() );
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
    if( mTileRenderThrowableListener != null ) {
      mTileRenderThrowableListener.onRenderThrow( throwable );
    }
  }

  boolean getRenderIsCancelled() {
    return mRenderIsCancelled;
  }

  public void destroy() {
    mTileRenderPoolExecutor.shutdownNow();
    clear();
    if( !mTileRenderThrottleHandler.hasMessages( RENDER_FLAG ) ) {
      mTileRenderThrottleHandler.removeMessages( RENDER_FLAG );
    }
  }

  @Override
  public void onDraw( Canvas canvas ) {
    super.onDraw( canvas );
    canvas.save();
    canvas.scale( mScale, mScale );
    canvas.getClipBounds( mDrawingRect );
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

  // ideally this would be part of TileRenderListener, but that's a breaking change
  public interface TileRenderThrowableListener {
    void onRenderThrow( Throwable throwable );
  }

  // This runnable is required to run on UI thread
  private Runnable mRenderPostExecuteRunnable = new Runnable() {
    @Override
    public void run() {
      if( !mTransitionsEnabled ) {
        cleanup();
      }
      if( mTileRenderListener != null ) {
        mTileRenderListener.onRenderComplete();
      }
      mLastRenderedDetailLevel = mDetailLevelToRender;
      requestRender();
    }
  };
}
