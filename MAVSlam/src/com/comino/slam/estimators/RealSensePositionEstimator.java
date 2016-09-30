/****************************************************************************
 *
 *   Copyright (c) 2016 Eike Mansfeld ecm@gmx.de. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in
 *    the documentation and/or other materials provided with the
 *    distribution.
 * 3. Neither the name of the copyright holder nor the names of its
 *    contributors may be used to endorse or promote products derived
 *    from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS
 * FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 * BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS
 * OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED
 * AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 * LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN
 * ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 *
 ****************************************************************************/

package com.comino.slam.estimators;


import java.awt.Graphics;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.LockSupport;

import org.mavlink.messages.MAV_SEVERITY;
import org.mavlink.messages.MSP_CMD;
import org.mavlink.messages.MSP_COMPONENT_CTRL;
import org.mavlink.messages.lquac.msg_msp_command;
import org.mavlink.messages.lquac.msg_msp_vision;
import org.mavlink.messages.lquac.msg_vision_position_estimate;
import org.mavlink.messages.lquac.msg_vision_speed_estimate;

import com.comino.mav.control.IMAVMSPController;
import com.comino.msp.main.MSPConfig;
import com.comino.msp.main.control.listener.IMAVLinkListener;
import com.comino.msp.model.DataModel;
import com.comino.msp.model.segment.LogMessage;
import com.comino.msp.utils.MSPMathUtils;
import com.comino.realsense.boofcv.RealSenseInfo;
import com.comino.realsense.boofcv.StreamRealSenseVisDepth;
import com.comino.realsense.boofcv.StreamRealSenseVisDepth.Listener;
import com.comino.realsense.boofcv.odometry.FactoryRealSenseOdometry;
import com.comino.realsense.boofcv.odometry.RealSenseDepthVisualOdometry;
import com.comino.server.mjpeg.MJPEGHandler;
import com.comino.slam.detectors.ISLAMDetector;

import boofcv.abst.feature.detect.interest.ConfigGeneralDetector;
import boofcv.abst.feature.tracker.PointTrackerTwoPass;
import boofcv.abst.sfm.AccessPointTracks3D;
import boofcv.alg.distort.DoNothingPixelTransform_F32;
import boofcv.alg.sfm.DepthSparse3D;
import boofcv.alg.tracker.klt.PkltConfig;
import boofcv.factory.feature.tracker.FactoryPointTrackerTwoPass;
import boofcv.struct.image.GrayS16;
import boofcv.struct.image.GrayU16;
import boofcv.struct.image.GrayU8;
import boofcv.struct.image.Planar;
import georegression.geometry.ConvertRotation3D_F64;
import georegression.geometry.GeometryMath_F64;
import georegression.struct.EulerType;
import georegression.struct.point.Point3D_F64;
import georegression.struct.point.Vector3D_F64;
import georegression.struct.se.Se3_F64;

public class RealSensePositionEstimator {

	private static final int    INIT_TIME_MS    	= 250;

	private static final float  MAX_SPEED   		= 2;

	private static final float  MAX_ROT_SPEED   	= 2f;

	private static final int    MIN_QUALITY 		= 15;
	private static final int    MAXTRACKS   		= 130;
	private static final int    RANSAC_ITERATIONS   = 250;

	private StreamRealSenseVisDepth realsense;
	private RealSenseDepthVisualOdometry<GrayU8,GrayU16> visualOdometry;

	private float oldTimeDepth_us=0;
	private float estTimeDepth_us=0;

	private Vector3D_F64 pos_raw;
	private Vector3D_F64 pos_raw_old = new Vector3D_F64();

	private Se3_F64 speed       	 = new Se3_F64();
	private Se3_F64 speed_ned        = new Se3_F64();
	private Se3_F64 speed_old        = new Se3_F64();
	private Se3_F64 pos_delta_ned    = new Se3_F64();
	private Se3_F64 pos_delta        = new Se3_F64();
	private Se3_F64 pos_ned          = new Se3_F64();
	private Se3_F64 pos              = new Se3_F64();

	private Se3_F64 vis_init         = new Se3_F64();

	private Se3_F64 cam_offset       = new Se3_F64();
	private Se3_F64 cam_offset_ned   = new Se3_F64();

	private Se3_F64 visToNED         = new Se3_F64();
	private Se3_F64 bodyToNED        = new Se3_F64();

	private long fps_tms   =0;
	private long init_tms  =0;

	private DataModel model;

	private boolean debug = false;

	private int quality=0;

	private boolean isRunning = false;
	private IMAVMSPController control;

	private int error_count = 0;
	private int init_count = 0;

	private boolean do_odometry = true;

	private long detector_tms = 0;
	private int  detector_cycle_ms = 250;

	private List<ISLAMDetector> detectors = null;

	public RealSensePositionEstimator(RealSenseInfo info, IMAVMSPController control, MSPConfig config, MJPEGHandler streamer ) {

		this.control = control;
		this.detectors = new ArrayList<ISLAMDetector>();

		this.debug = config.getBoolProperty("vision_debug", "false");
		System.out.println("Vision debugging: "+debug);

		this.detector_cycle_ms = config.getIntProperty("vision_detector_cycle", "250");
		if(this.detector_cycle_ms>0)
			System.out.printf("Vision detectors enablied with %2 [ms] cycle \n",detector_cycle_ms);

		this.cam_offset.T.z = -config.getFloatProperty("vision_x_offset", "0.0");
		this.cam_offset.T.x = -config.getFloatProperty("vision_y_offset", "0.0");
		this.cam_offset.T.y = -config.getFloatProperty("vision_z_offset", "0.0");

		System.out.printf("Vision position offset: %s\n",this.cam_offset.T);

		this.model = control.getCurrentModel();

		control.registerListener(msg_msp_command.class, new IMAVLinkListener() {
			@Override
			public void received(Object o) {
				msg_msp_command cmd = (msg_msp_command)o;
				switch(cmd.command) {
				case MSP_CMD.MSP_CMD_VISION:
					if((int)(cmd.param1)==MSP_COMPONENT_CTRL.ENABLE) {
						do_odometry = true; init("Init"); break;
					}
					if((int)(cmd.param1)==MSP_COMPONENT_CTRL.DISABLE) {
						do_odometry = false; break; };
						break;
				}
			}
		});

		try {
			realsense = new StreamRealSenseVisDepth(0,info);
		} catch(Exception e) {

		}

		PkltConfig configKlt = new PkltConfig();
		configKlt.pyramidScaling = new int[]{1, 2, 4, 8};
		configKlt.templateRadius = 3;

		PointTrackerTwoPass<GrayU8> tracker =
				FactoryPointTrackerTwoPass.klt(configKlt, new ConfigGeneralDetector(MAXTRACKS, 2, 1),
						GrayU8.class, GrayS16.class);

		DepthSparse3D<GrayU16> sparseDepth = new DepthSparse3D.I<GrayU16>(1e-3);

		visualOdometry = FactoryRealSenseOdometry.depthDepthPnP(1.2, 120, 2, RANSAC_ITERATIONS, 50, true,
				sparseDepth, tracker, GrayU8.class, GrayU16.class);

		visualOdometry.setCalibration(realsense.getIntrinsics(),new DoNothingPixelTransform_F32());

		if(debug) {
			streamer.registerOverlayListener(ctx -> {
				overlayFeatures(ctx);
			});
		}

		init_count = 0;

		realsense.registerListener(new Listener() {

			float fps; float dt; int mf=0; int fpm;
			float ang_speed; float odo_speed;

			@Override
			public void process(Planar<GrayU8> rgb, GrayU16 depth, long timeRgb, long timeDepth) {

				if(dt >0) {
					fpm += (int)(1f/dt+0.5f);
					if((System.currentTimeMillis() - fps_tms) > 500) {
						fps_tms = System.currentTimeMillis();
						if(mf>0)
							fps = fpm/mf;
						mf=0; fpm=0;
					}
					mf++;
				}

				if(streamer!=null)
					streamer.addImage(rgb.bands[0]);

				if(!do_odometry) {
					msg_msp_vision msg = new msg_msp_vision(1,2);
					msg.x =  Float.NaN;
					msg.y =  Float.NaN;
					msg.z =  Float.NaN;
					msg.vx = Float.NaN;
					msg.vy = Float.NaN;
					msg.vz = Float.NaN;
					msg.h = MSPMathUtils.fromRad((float)vis_init.getY());
					msg.quality = quality;
					msg.fps = fps;
					msg.errors = error_count;
					msg.flags = msg.flags & 1;
					msg.tms = System.nanoTime() / 1000;
					control.sendMAVLinkMessage(msg);
					return;
				}

				// Check PX4 rotation and reset odometry if rotating too fast
				ang_speed = (float)Math.sqrt(model.attitude.pr * model.attitude.pr +
						model.attitude.rr * model.attitude.rr +
						model.attitude.yr * model.attitude.yr);

				if(ang_speed > MAX_ROT_SPEED) {
					if(debug)
						System.out.println("[vis] Rotation "+ang_speed+" > MAX");
					init("IMU.Rot.speed");
					return;
				}


				if( !visualOdometry.process(rgb.getBand(0),depth) ) {
					if(debug)
						System.out.println("[vis] Odometry failure");
					init("odometry");
					return;
				}

				if((System.currentTimeMillis()-init_tms) < INIT_TIME_MS) {

					vis_init.getTranslation().z = vis_init.getTranslation().z * init_count + model.attitude.r;
					vis_init.getTranslation().x = vis_init.getTranslation().x * init_count + model.attitude.p;
					vis_init.getTranslation().y = vis_init.getTranslation().y * init_count + model.attitude.y;

					vis_init.getTranslation().scale(1d/(++init_count));

					ConvertRotation3D_F64.eulerToMatrix(EulerType.XYZ,
							vis_init.getTranslation().x,
							vis_init.getTranslation().y,
							vis_init.getTranslation().z,
							visToNED.getRotation());

					pos_raw_old.set(0,0,0);
					pos.reset();
					return;
				}

				estTimeDepth_us = timeDepth*1000;
				if(oldTimeDepth_us>0)
				  dt = (estTimeDepth_us - oldTimeDepth_us)/1000000f;

				oldTimeDepth_us = estTimeDepth_us;

				pos_raw = visualOdometry.getCameraToWorld().getT();

				ConvertRotation3D_F64.eulerToMatrix(EulerType.ZXY,
						model.attitude.r,
						model.attitude.p,
						model.attitude.y,
						bodyToNED.getRotation());

				cam_offset.concat(bodyToNED, cam_offset_ned);

				quality = visualOdometry.getInlierCount() *100 / MAXTRACKS ;

				if(!pos_raw_old.isIdentical(0, 0, 0) && dt > 0) {

					if(quality > MIN_QUALITY ) {

						// speed.T = (pos_raw - pos_raw_old ) / dt
						GeometryMath_F64.sub(pos_raw, pos_raw_old, speed.T);
						speed.T.scale(1d/dt);

//						Extreme filtering has no effect on accuracy
//						speed.T.z = speed.T.z * 0.2f + speed_old.T.z * 0.8f;
//						speed.T.x = speed.T.x * 0.2f + speed_old.T.x * 0.8f;

					} else {
						if(debug)
							System.out.println("[vis] Quality "+quality+" < Min");
						init("Quality");
						error_count++;
						return;
					}

					odo_speed = (float) speed.T.norm();
					speed_old.T.set(speed.T);

					if(odo_speed < MAX_SPEED) {

						// pos_delta.T = speed.T * dt
						pos_delta.T.set(speed.T); pos_delta.T.scale(dt);
						// rotate to NED
						pos_delta.concat(visToNED, pos_delta_ned);
						speed.concat(visToNED, speed_ned);

					} else {
						init("Odomery speed");
						return;
					}

					// pos.T = pos.T + pos_delta.T
					pos.T.plusIP(pos_delta_ned.T);

					// pos_ned.T = pos.T + camm_offset_ned.T
					pos_ned.T.set(pos.T); pos_ned.T.plusIP(cam_offset_ned.T);
				}
				pos_raw_old.set(pos_raw);

				if(control!=null) {

					msg_vision_position_estimate sms = new msg_vision_position_estimate(1,1);
					sms.usec = System.nanoTime()/1000; //(long)estTimeDepth_us;
					sms.x = (float) pos_ned.T.z;
					sms.y = (float) pos_ned.T.x;
					sms.z = (float) pos_ned.T.y;
					control.sendMAVLinkMessage(sms);

/* Currently not supported by PX4
					msg_vision_speed_estimate sse = new msg_vision_speed_estimate(1,1);
					sse.usec = (long)estTimeDepth_us;
					sse.x = (float) speed_ned.T.z;
					sse.y = (float) speed_ned.T.x;
					sse.z = (float) speed_ned.T.y;
					control.sendMAVLinkMessage(sse);
*/

					LockSupport.parkNanos(2000000);

					msg_msp_vision msg = new msg_msp_vision(1,2);
					msg.x =  (float) pos_ned.T.z;
					msg.y =  (float) pos_ned.T.x;
					msg.z =  (float) pos_ned.T.y;
					msg.vx = (float) speed_ned.T.z;
					msg.vy = (float) speed_ned.T.x;
					msg.vz = (float) speed_ned.T.y;
					msg.h = MSPMathUtils.fromRad((float)vis_init.getY());
					msg.quality = quality;
					msg.fps = fps;
					msg.errors = error_count;
					msg.flags = msg.flags | 1;
					msg.tms = (long)estTimeDepth_us;
					control.sendMAVLinkMessage(msg);
				}

				if(detectors.size()>0 && detector_cycle_ms>0) {
					if((System.currentTimeMillis() - detector_tms) > detector_cycle_ms) {
						detector_tms = System.currentTimeMillis();
						for(ISLAMDetector d : detectors)
							d.process(visualOdometry, depth, rgb);

					}
				}
			}
		});
		init_tms = System.currentTimeMillis()+5000;
	}

	private void overlayFeatures(Graphics ctx) {
		AccessPointTracks3D points = (AccessPointTracks3D)visualOdometry;
		for( int i = 0; i < points.getAllTracks().size(); i++ ) {
			if(points.isInlier(i))
				ctx.drawRect((int)points.getAllTracks().get(i).x,(int)points.getAllTracks().get(i).y, 1, 1);
		}
	}

	public RealSensePositionEstimator() {
		this(new RealSenseInfo(320,240, RealSenseInfo.MODE_RGB), null, MSPConfig.getInstance("msp.properties"),null);
	}

	public void registerDetector(ISLAMDetector detector) {
		if(detector_cycle_ms>0) {
			System.out.println("[vis] Vision detector registered: "+detector.getClass().getSimpleName());
			detectors.add(detector);
		}
	}

	public void start() {
		isRunning = true; init_tms=0;
		init("StartUp");
		if(realsense!=null)
			realsense.start();
	}

	public void stop() {
		if(isRunning) {
			realsense.stop();

			msg_msp_vision msg = new msg_msp_vision(1,2);
			msg.x = Float.NaN;
			msg.y = Float.NaN;
			msg.z = Float.NaN;
			msg.h = MSPMathUtils.fromRad((float)vis_init.getY());
			msg.quality = 0;
			msg.fps = 0;
			msg.flags = 0;
			msg.tms = System.nanoTime() / 1000;
			control.sendMAVLinkMessage(msg);

		}
		isRunning=false;
	}

	public boolean isRunning() {
		return isRunning;
	}

	private void init(String msg) {
		if((System.currentTimeMillis()-init_tms)>INIT_TIME_MS) {
			control.writeLogMessage(new LogMessage("[vis] reset odometry: "+msg,
					MAV_SEVERITY.MAV_SEVERITY_WARNING));
			visualOdometry.reset();
			init_count = 0;
			error_count=0;
			vis_init.reset();
			init_tms = System.currentTimeMillis();
		}
	}

	public static void main(String[] args) {
		new RealSensePositionEstimator();
	}

}
