import type { TestInfo } from '@playwright/test';

/** Documentation images change only when DOC_SCREENSHOTS=1; other runs write test-results/. */
export const docShot = (info: TestInfo, image: string) =>
  process.env.DOC_SCREENSHOTS === '1' ? '../docs/images/' + image : info.outputPath(image);
