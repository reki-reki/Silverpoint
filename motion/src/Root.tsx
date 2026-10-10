import React from 'react';
import { Composition } from 'remotion';
import { Intro } from './Intro';

// Square, the width of a 1080 px phone screen; the app plays it centred on black.
export const Root: React.FC = () => (
  <Composition id="Intro" component={Intro} durationInFrames={180} fps={60} width={1080} height={1080} />
);
