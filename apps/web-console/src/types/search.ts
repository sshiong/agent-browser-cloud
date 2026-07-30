export type SearchResourceType =
  'SESSION' | 'PROFILE' | 'GROUP' | 'TAG' | 'RUNTIME' | 'NODE';

export interface GlobalSearchResult {
  resourceType: SearchResourceType;
  resourceId: string;
  title: string;
  description: string | null;
  status: string | null;
  region: string | null;
  updatedAt: string | null;
}

export interface GlobalSearchResponse {
  query: string;
  items: GlobalSearchResult[];
  limit: number;
  truncated: boolean;
}
