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

package com.comino.slam.main;

import java.io.IOException;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.net.InetSocketAddress;

import org.mavlink.messages.MAV_CMD;
import org.mavlink.messages.lquac.msg_msp_status;

import com.comino.mav.control.IMAVMSPController;
import com.comino.mav.control.impl.MAVProxyController;
import com.comino.mav.control.impl.MAVProxyController2;
import com.comino.msp.log.MSPLogger;
import com.comino.msp.main.MSPConfig;
import com.comino.msp.main.commander.MSPCommander;
import com.comino.msp.model.segment.Status;
import com.comino.realsense.boofcv.RealSenseInfo;
import com.comino.server.mjpeg.MJPEGHandler;
import com.comino.slam.detectors.impl.SimpleCollisionDetector;
import com.comino.slam.estimators.RealSensePositionEstimator;
import com.sun.net.httpserver.HttpServer;

public class StartUp implements Runnable {

	IMAVMSPController    control = null;
	MSPConfig	          config  = null;

	private OperatingSystemMXBean osBean = null;
	private MemoryMXBean mxBean = null;

	private MSPCommander  commander = null;

	RealSensePositionEstimator vision = null;

	public StartUp(String[] args) {

		config  = MSPConfig.getInstance("msp.properties");
		System.out.println("MSPControlService version "+config.getVersion());

		if(args.length>0)
			control = new MAVProxyController2(true);
		else
			control = new MAVProxyController2(false);

		osBean =  java.lang.management.ManagementFactory.getOperatingSystemMXBean();
		mxBean = java.lang.management.ManagementFactory.getMemoryMXBean();

		MSPLogger.getInstance(control);

		commander = new MSPCommander(control);

		// Start services if required

		RealSenseInfo info = new RealSenseInfo(640,480, RealSenseInfo.MODE_RGB);

		MJPEGHandler streamer = new MJPEGHandler(info, control.getCurrentModel());

		try {
			if(config.getBoolProperty("vision_enabled", "true")) {
				vision = new RealSensePositionEstimator(info, control, config,streamer);
			vision.registerDetector(new SimpleCollisionDetector(control,streamer));
			}
		} catch(Exception e) {
			System.out.println("[vis] Vision not available: "+e.getMessage());
		}

		// reset odometry to set initial heading properly
		control.addStatusChangeListener((ov,nv) -> {
			if(nv.isStatusChanged(ov,Status.MSP_MODE_POSITION)) {
				if(vision!=null)
					 vision.reset();
			}
		});


		if(vision!=null && !vision.isRunning())
			vision.start();


		// register MSP commands here

		control.start();

		MSPLogger.getInstance().writeLocalMsg("MAVProxy "+config.getVersion()+" loaded");
		Thread worker = new Thread(this);
		worker.start();

		// Start HTTP Service with MJPEG streamer

		HttpServer server;
		try {
			server = HttpServer.create(new InetSocketAddress(8080),2);
			server.createContext("/mjpeg", streamer);
			server.setExecutor(null); // creates a default executor
			server.start();
		} catch (IOException e) {
			System.err.println(e.getMessage());
		}


	}



	public static void main(String[] args) {
		new StartUp(args);

	}



	@Override
	public void run() {
		long tms = System.currentTimeMillis();

		while(true) {
			try {
				Thread.sleep(500);


				if(!control.isConnected()) {
					control.connect();
					continue;
				}

				msg_msp_status msg = new msg_msp_status(2,1);
				msg.load = (int)(osBean.getSystemLoadAverage()*100);
				msg.memory = (int)(mxBean.getHeapMemoryUsage().getUsed() * 100 /mxBean.getHeapMemoryUsage().getMax());
				msg.com_error = control.getErrorCount();
				msg.uptime_ms = System.currentTimeMillis() - tms;
				msg.status = control.getCurrentModel().sys.getStatus();
				msg.setVersion(config.getVersion());
				msg.setArch(osBean.getArch());
				msg.unix_time_us = System.currentTimeMillis() * 1000;
				control.sendMAVLinkMessage(msg);

			} catch (Exception e) {
				control.close();
			}
		}

	}

}
