import { createContext, useContext } from 'react';
import type { Meta } from '../api/types';

export const MetaContext = createContext<Meta | null>(null);

export function useMeta(): Meta | null {
  return useContext(MetaContext);
}
