import { Config } from '@remotion/cli/config';

// Use an installed Chromium when one is given (CI, containers without downloads).
if (process.env.REMOTION_BROWSER) Config.setBrowserExecutable(process.env.REMOTION_BROWSER);
Config.setVideoImageFormat('png');
