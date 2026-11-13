import React from 'react';
import { Composition, registerRoot } from 'remotion';
import { Demo, FPS, DURATION } from './Demo';

registerRoot(() => <Composition id="BackroomDemo" component={Demo} durationInFrames={DURATION}
  fps={FPS} width={1920} height={1080} />);
