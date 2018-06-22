SFPlayer {
	var <path, <outbus, <server, <bufnum, <sf, cond, curNode, curTime, <curSynth, <synthName;
	var <window, bounds, outMenu, playButton, ampSlider, ampNumber;
	var <amp, <isPlaying = false, wasPlaying, hasGUI, <startTime, timeString, <sfView, guiRoutine;
	var scope, iEnv, clock;
	var <cues, offset, cueMenu, lastStart, cueOffsetNum, <skin;
	var <openFilePending = false, <openGUIafterLoading = false, tempBounds, tempAction, <>duplicateSingleChannel = true;
	var <ampSpec;
	var rateVar, addActionVar, targetVar, bufsizeVar;
	var <>switchTargetWhilePlaying = false;

	*new {arg path, outbus, server, skin;
		^super.newCopyArgs(path, outbus, server).initSFPlayer(skin);
	}

	initSFPlayer {arg argSkin;
		skin = argSkin ?? {SFPlayerSkin.default};
		server = server ?? Server.default;
		this.rate_(1);
		// rateVar = 1;
		this.addAction_(0);
		// addActionVar = 0;
		// targetVar = 1;
		this.target_(1);
		outbus ?? {this.outbus_(0)};
		bufsizeVar = 65536 * 8;
		// server.serverRunning.not({server.boot}); //this was not working (missing .if); we have waitForBoot in runSetup anyway
		offset = 0;
		path.isNil.if({
			openFilePending = true;
			// Dialog.getPaths({arg paths;
			Dialog.openPanel({arg paths;
				// path = paths[0];
				path = paths;
				openFilePending = false;
				this.runSetup;
			})
		}, {
			openFilePending = false;
			this.runSetup;
		})
	}

	runSetup {
		sf = SoundFile.new;
		{sf.openRead(path)}.try({"Soundfile could not be opened".warn});
		cond = Condition.new;
		if(server.options.numOutputBusChannels < sf.numChannels, {
			if(server.serverRunning.not, { //if server is not running, set the number of output channels
				format("%: setting server's options.numOutputBusChannels to %", this.class.name, sf.numChannels).postln;
				server.options.numOutputBusChannels_(sf.numChannels);
			}, {
				format("%: server's options.numOutputBusChannels (%) is lower than soundfile's numChannels (%)", this.class.name, server.options.numOutputBusChannels, sf.numChannels).warn;
			})
		});
		server.waitForBoot({
			this.buildSD;
		});
		sf.close;
		amp = 1;
		isPlaying = false;
		wasPlaying = false;
		hasGUI = false;
		startTime = 0.0;
		if(openGUIafterLoading, {
			this.gui(tempBounds, tempAction);
			openGUIafterLoading = false;
		});
	}

	buildSD {
		synthName = "SFPlayer"++sf.numChannels;
		SynthDef(synthName, {arg gate = 1, buffer, amp = 1, rate = 1, outbus;
			var diskin;
			diskin = VDiskIn.ar(sf.numChannels, buffer, (BufSampleRate.kr(buffer) / SampleRate.ir) * rate);
			((sf.numChannels == 1) && duplicateSingleChannel).if({
				diskin = diskin.dup
			});
			Out.ar(outbus, diskin *
				EnvGen.kr(Env([0, 1, 0], [0.02, 0.02], \sin, 1), gate, doneAction: 2) *
				Lag.kr(amp, 0.1))
		}).add;
	}

	loadBuffer {arg bufsize = 65536, startTime = 0;
		bufsize = bufsize * sf.numChannels;
		server.sendMsg(\b_alloc, bufnum = server.bufferAllocator.alloc, bufsize, sf.numChannels,
			[\b_read, bufnum, path, startTime * sf.sampleRate, bufsize, 0, 1]);
	}

	bufsize {^bufsizeVar}
	bufsize_ {arg val; bufsizeVar = val}

	addAction  {^addActionVar}
	addAction_ {arg val;
		// val = Node.addActions[val];
		val !? {
			addActionVar = val;
			this.changed(\addAction, this.addAction);
		};
	}

	target {^targetVar}
	target_ {arg val;
		targetVar = val;
		this.changed(\target, val, switchTargetWhilePlaying);
		if(isPlaying && switchTargetWhilePlaying, {
			Node.actionNumberFor(this.addAction).switch(
				0, {curSynth.moveToHead(this.target)}, //head
				1, {curSynth.moveToTail(this.target)}, //tail
				2, {curSynth.moveBefore(this.target)}, //before
				3, {curSynth.moveAfter(this.target)}, //after
			)
		});
	}

	rate {^rateVar}
	rate_ {arg val;
		rateVar = val;
		curSynth.set(\rate, rateVar);
		this.changed(\rate, this.rate);
	}

	play {arg bufsize, addAction, target, rate;
		bufsize !? {bufsizeVar = bufsize};
		addAction !? {addActionVar = addAction};
		target !? {targetVar = target};
		rate !? {rateVar = rate};
		(isPlaying.not and: {startTime < sf.duration} and: synthName.notNil).if({
			Routine.run({
				clock = TempoClock.new;
				lastStart = startTime;
				clock.sched(sf.duration - startTime + 0.1, {this.stop});
				this.loadBuffer(bufsizeVar, startTime);
				server.sync(cond);
				// server.sendMsg(\s_new, "SFPlayer"++sf.numChannels,
				// curNode = server.nodeAllocator.alloc(1), addAction, target,
				// \buffer, bufnum, \amp, amp, \outbus, outbus, \rate, rate);
				curSynth = Synth(synthName, [\buffer, bufnum, \amp, amp, \outbus, outbus, \rate, rateVar], targetVar, addActionVar);
				curNode = curSynth.nodeID;
				isPlaying = true;
				this.changed(\isPlaying, this.isPlaying);
				// hasGUI.if({
				// this.playGUIRoutine
			// })
			})
		})
	}

	pause {
		isPlaying.if({
			this.stop( false );
			this.startTime_( curTime );
		})
	}

	stop {arg updateStart = true;
		var oldbufnum;
		isPlaying.if({
			clock.stop;
			// server.sendMsg(\n_set, curNode, \gate, 0);
			curSynth.release;
			oldbufnum = bufnum;
			// this.stopGUIRoutine;
			isPlaying = false;
			this.changed(\isPlaying, this.isPlaying, updateStart);
			SystemClock.sched(0.2, {
				server.sendBundle(nil, [\b_close, oldbufnum], [\b_free, oldbufnum]);
				server.bufferAllocator.free(oldbufnum)
			});
			updateStart.if({{this.startTime_(lastStart)}.defer(0.1)});
			// hasGUI.if({
			// 	{playButton.value_(0)}.defer;
			// })
		})
	}

	outbus_ {arg newOut, updateMenu = true;
		outbus = newOut;
		isPlaying.if({
			server.sendMsg(\n_set, curNode, \outbus, outbus);
		});
		this.changed(\outbus, outbus, updateMenu);
		// (hasGUI and: {updateMenu}).if({
		// 	outMenu.value_(outbus)
		// })
	}

	amp_ {arg newAmp, source; //source: \number, \slider or none
		amp = newAmp;
		isPlaying.if({
			server.sendMsg(\n_set, curNode, \amp, amp)
		});
		this.changed(\amp, amp, source);
		// hasGUI.if({
		// 	// ampNumber.valueAction_(newAmp.ampdb);
		// 	ampNumber.value_(newAmp.ampdb);
		// 	ampSlider.value_(ampSpec.unmap(newAmp.ampdb));
		// })
	}

	startTime_ {arg newStartTime;
		startTime = (newStartTime + offset).max(0).min(sf.duration);
		this.changed(\startTime, startTime);
		// hasGUI.if({
		// 	sfView.timeCursorPosition_((startTime * sf.sampleRate).round);
		// 	timeString.string_(startTime.asTimeString[3..10]);
		// 	cueOffsetNum.value_(0);
		// 	offset = 0;
		// })
	}

	gui {arg argBounds, doneAction;
		var wasPlaying;
		if(openFilePending, {
			tempBounds = argBounds;
			tempAction = doneAction;
			openGUIafterLoading = true;
		}, {
			ampSpec = [-90, 12].asSpec;
			bounds = argBounds ?? {Rect(200, 200, 980, 600)};
			window = Window(path.basename, bounds);
			window.view.background_(skin.background);
			window.onClose_({isPlaying.if({this.stop}); hasGUI = false});
			hasGUI = true;
			window.view.layout_(
				VLayout(
					HLayout(
						timeString = StaticText(window)
						.font_(Font("Arial", 72))
						.stringColor_(skin.string)
						.string_(startTime.asTimeString[3..10])
						.fixedWidth_(300),
						VLayout(
							// Play / Pause button
							playButton = Button.new(window)
							.states_([
								[">", skin.string, skin.background],
								["||", skin.string, skin.background]])
							.focus(true)
							.action_({arg button;
								[{this.pause}, {this.play}][button.value].value;
							})
							.minWidth_(120),
							Button.new(window, Rect(310, 40, 120, 20))
							.states_([
								["[]", skin.string, skin.background]])
							.canFocus_(false)
							.action_({this.stop}),
							Button.new(window, Rect(310, 70, 120, 20))
							.states_([
								["Scope On", skin.string, skin.background],
								["Scope Off", skin.string, skin.background]
							])
							.canFocus_(false)
							.action_({arg button;
								[
									{scope.window.close},
									{scope = server.scope(sf.numChannels, outbus)}
								][button.value].value;
							})
						).margins_([10, 0, 0, 10]),
						VLayout(
							HLayout(
								StaticText(window)
								.string_("Outbus")
								.stringColor_(skin.string),
								outMenu = PopUpMenu(window)
								.items_(server.options.numAudioBusChannels.collect({arg i; i.asString}))
								.value_(outbus ?? {0})
								.action_({arg menu; this.outbus_(menu.value, false); playButton.focus(true)})
								.stringColor_( skin.string )
							),
							nil,
						).margins_([10, 0, 0, 10]), //outbus
						VLayout(
							HLayout(
								StaticText(window)
								.string_("Amplitude (in db)")
								.stringColor_( skin.string),
								ampNumber = NumberBox(window)
								.value_(amp.ampdb)
								.action_({arg me;
									this.amp_(me.value.dbamp, \number);
									// ampSlider.value_(ampSpec.unmap(me.value);
									// playButton.focus(true));
									playButton.focus(true);
								}).maxWidth_(60),
								nil
							),
							ampSlider = Slider(window)
							.value_(ampSpec.unmap(amp))
							.canFocus_(false)
							.orientation_(\horizontal)
							.action_({arg me;
								this.amp_(ampSpec.map(me.value).round(0.1).dbamp, \slider);
								// ampNumber.value_(ampSpec.map(me.value).round(0.1))
							})
							.fixedSize_(240@24),
							HLayout(
								Button.new(window)
								.states_([
									["Reset", skin.string, skin.background]
								])
								.canFocus_(false)
								.action_({this.reset}),
								nil
							)
						).margins_([10, 0, 0, 10]), //amp etc
						[nil, stretch: 2], //empty space on the right
					), //end of top section with time, play/stop, amp etc

					[
						sfView = SoundFileView.new(window)
						.canFocus_(false)
						.soundfile_(sf)
						.timeCursorColor_(skin.sfCursor)
						.readWithTask(0, sf.numFrames
							, block: 64,
							doneAction: {window.front; doneAction.value})
						.gridOn_(false)
						.timeCursorOn_(true)
						.background_(skin.sfBackground)
						.waveColors_(Array.fill(sf.numChannels, skin.sfWaveform))
						.mouseDownAction_({this.pausePlay})
						.mouseUpAction_({this.playPaused})
						.timeCursorPosition_(0 / sf.duration),
						stretch: 10
					],

					//bottom secion
					/* cues */
					HLayout(
						GridLayout.rows(
							[
								StaticText(window)
								.string_("Play From Cue:")
								.stringColor_( skin.string),
								[
									cueMenu = PopUpMenu(window)
									.items_(cues.asArray)
									.stringColor_(skin.string)
									.canFocus_(false)
									.mouseUpAction_({"MouseUp".postln;})
									.mouseDownAction_({arg view;
										isPlaying.if({wasPlaying = true;this.stop});
										view.value_(0)
									})
									.action_({arg thisMenu;
										var idx;
										idx = thisMenu.value - 1;
										(idx >= 0).if({
											this.playFromCue(cues[idx][0], idx);
										}, {
											this.playFromCue(\none, -1)
										});
										wasPlaying.if({this.play; wasPlaying = false;})
									})
									.minWidth_(300),
									columns: 2
								],
								Button(window)
								.states_([
									["Load cues", skin.string, skin.background]
								])
								.canFocus_(false)
								.action_({
									this.loadCues
								}),
								Button(window)
								.states_([
									["Save cues", skin.string, skin.background]
								])
								.canFocus_(false)
								.action_({
									this.saveCues
								}),
								StaticText(window)
								.string_("Add cues ([\\key, val])")
								.stringColor_(skin.string),
								TextField(window)
								.action_({arg me;
									var vals;
									vals = me.string.interpret;
									vals.isKindOf(Array).if({
										vals = vals.flat.clump(2);
									}, {
										vals = vals.asArray.flat.clump(2)
									});
									vals[0].isKindOf(Array).if({
										vals.do({arg thisPair;
											this.addCue(thisPair[0], thisPair[1], false)
										});
										this.sortCues;
									}, {
										this.addCue(vals[0], vals[1], true)
									});
									playButton.focus(true);
								}),
								// [nil, rows: 2, stretch: 1]
							], [
								StaticText(window)
								.string_("Cue offset:")
								.stringColor_(skin.string),
								cueOffsetNum = NumberBox(window)
								.value_(0)
								.action_({arg thisBox;
									this.offset_(thisBox.value);
									playButton.focus(true)
								})
								.maxWidth_(60),
								nil,
								Button(window)
								.states_([
									["Hide cues",  skin.string, skin.background],
									["Show cues",  skin.string, skin.background]
								])
								.canFocus_(false)
								.action_({arg button;
									(button.value == 0).if({
										this.drawCues
									}, {
										this.hideCues
									})
								}),
								nil,
								StaticText(window)
								.string_("Remove cues ([\\key])")
								.stringColor_(skin.string),
								TextField(window)
								.action_({arg me;
									var vals;
									vals = me.string.interpret;
									vals.isKindOf(Array).if({
										vals = vals.flat;
									}, {
										vals = vals.asArray
									});
									vals.do({arg thisKey;
										this.removeCue(thisKey, false)
									});
									this.sortCues;
									playButton.focus(true);
								})
							]
						).hSpacing_(12).vSpacing_(4),
						[nil, stretch: 1]
					)
				)
			);
			window.front;
			this.addDependant(this);
			window.onClose_({this.removeDependant(this)});
		});
	}

	pausePlay {
		isPlaying.if({
			wasPlaying = true;
			this.stop;
		});
	}

	playPaused {
		startTime = sfView.timeCursorPosition / sf.sampleRate;
		wasPlaying.if({
			this.play;
			wasPlaying = false;
		}, {
			timeString.string_(startTime.asTimeString[3..10]);
		})
	}

	playGUIRoutine {
		guiRoutine = Routine.run({
			var now;
			now = Main.elapsedTime;
			loop({
				curTime = (((Main.elapsedTime - now) * rateVar) + startTime);
				{
					hasGUI.if({
						sfView.timeCursorPosition_(curTime * sf.sampleRate);
						timeString.string_(curTime.round(0.01).asTimeString[3..10]);
					})
				}.defer;
				0.1.wait;
			})
		})
	}

	stopGUIRoutine {
		guiRoutine.stop;
	}

	addCue {arg key, time, sort = true, redraw = true;
		(time < sf.duration).if({
			//			(cues.notNil and: {cues[key].notNil}).if({
			//				this.removeCue(key, false, false);
			//			});
			cues = cues.add([key, time]);
		}, {
			"You tried to add a cue past the end of the soundfile".warn;
		});
		sort.if({this.sortCues});
		redraw.if({this.drawCues});
	}

	removeCue {arg key, sort = true, redraw = true;
		var idx;
		cues.do({arg thisCue, i;
			(thisCue[0] == key).if({
				idx = i;
			});
		});
		idx.notNil.if({
			cues.removeAt(idx);
		}, {
			"Cue not found".warn
		});
		sort.if({	this.sortCues;});
		redraw.if({this.drawCues});
	}

	sortCues {
		cues.sort({arg a, b; a[1] < b[1]});
	}

	loadCues {arg path;
		path.isNil.if({
			Dialog.getPaths({arg paths;
				cues = paths[0].load;
				this.drawCues;
			})
		}, {
			cues = path.load;
			this.drawCues;
		});
	}

	saveCues {arg path;
		path.isNil.if({
			Dialog.savePanel({arg thisPath;
				cues.writeArchive(thisPath);
			})
		}, {
			cues.writeArchive(path)
		})
	}

	hideCues {
		hasGUI.if({
			window.drawHook_({});
			window.refresh;
		});
	}

	drawCues {
		var points, menuItems, nTabs, inc;
		cues.notNil.if({
			hasGUI.if({
				this.sortCues;
				points = cues.collect({arg thisCue;
					thisCue[1];
				});
				points = points / sf.duration * 900 + 20;
				window.drawHook_({
					Pen.font_(Font("Helvetica", 14));
					Pen.strokeColor_(skin.cueLine);
					points.do({arg thisPoint, i;
						Pen.moveTo(thisPoint @ 100);
						Pen.lineTo(thisPoint @ 500);
						Pen.stroke;
					});
					Pen.fillColor_(skin.cueLabel);
					points.do({arg thisPoint, i;
						Pen.stringAtPoint(cues[i][0].asString,
							(thisPoint + 3) @ (100 + ((i % 10) * 40)));
						Pen.fillStroke;
					})
				});
				menuItems = cues.collect({arg thisCue;
					var key, time, nTabs;
					(thisCue[0].asString.size > 6).if({
						// nTabs = "\t\t"
						nTabs = "\t"
					}, {
						// nTabs = "\t\t\t"
						nTabs = "\t"
					});
					thisCue[0].asString + nTabs + thisCue[1].asTimeString;
				});
				// cueMenu.items_(["None" + "\t\t\t" + 0.asTimeString] ++ menuItems);
				cueMenu.items_(["None" + "\t" + 0.asTimeString] ++ menuItems);
				window.refresh;
			})
		})
	}

	playFromCue {arg key, idx;
		var newStart, tmp;
		idx = idx ?? {
			cues.do({arg thisCue, i;
				(thisCue[0] == key).if({
					tmp = i;
				});
			});
			tmp;
		};
		idx.notNil.if({
			(idx == -1).if({
				newStart = 0.0;
			}, {
				newStart = cues[idx][1];
			})
		});
		this.startTime_(newStart);
	}

	offset_ {arg newOffset;
		var tmp;
		tmp = startTime + offset;
		offset = newOffset;
		this.startTime_(tmp);
	}

	reset {
		isPlaying.if({this.stop});
		{
			cueMenu.value_(0);
			this.startTime_(0);
			this.amp_(1);
		}.defer(0.11)
	}

	update {arg who, what ...args;
		var value = args[0];
		// args[0] is the value
		// "update fired, what: ".post;
		// what.post;
		// ": ".post; args.postln;
		{
			what.switch(
				\addAction, {},
				\amp, {
					hasGUI.if({
						if(args[1] != \number, {
						ampNumber.value_(value.ampdb.round(0.1));
						}, {"not updating number".postln;});
						if(args[1] != \slider, {
							ampSlider.value_(ampSpec.unmap(value.ampdb));
						});
					})
				},
				\outbus, {
					var updMenu = args[1];
					(hasGUI && updMenu).if({
						outMenu.value_(value)
					})
				},
				\isPlaying, {
					if(hasGUI, {
						if(value, {
							this.playGUIRoutine;
						}, {
							this.stopGUIRoutine;
							playButton.value_(0);
						})
					})
				},
				\startTime, {
					hasGUI.if({
						sfView.timeCursorPosition_((startTime * sf.sampleRate).round);
						timeString.string_(startTime.asTimeString[3..10]);
						cueOffsetNum.value_(0);
						offset = 0;
					})
				}
			)
		}.defer;
	}

}

SFPlayerSkin {
	classvar <>default;
	var <>string, <>background, <>sfBackground, <>sfWaveform, <>sfCursor,
	<>cueLabel, <>cueLine;

	*new {arg string, background, sfBackground, sfWaveform, sfCursor, cueLabel, cueLine;
		^super.newCopyArgs(string, background, sfBackground, sfWaveform,
			sfCursor, cueLabel, cueLine).initSFPlayerSkin;
	}

	initSFPlayerSkin {
		string = string ?? {Color.grey(0.8)};
		background = background ?? {Color.grey(0.4)};
		sfBackground = sfBackground ?? {Color.grey(0.8)};
		sfWaveform = sfWaveform ?? {Color(0.4, 0.4, 0.7)};
		sfCursor = sfCursor ?? {Color.blue};
		cueLabel = cueLabel ?? {Color.black};
		cueLine = cueLine ?? {Color.grey(0.2)}
	}

	*initClass {
		default = this.new;
	}
}
