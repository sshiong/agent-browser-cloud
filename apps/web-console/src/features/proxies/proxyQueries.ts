import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  createProxyBinding,
  deleteProxyBinding,
  getProxyOverview,
  listProxyBindings,
  updateProxyBinding,
} from '@/api/proxy';
import type { ProxyBindingRequest } from '@/types/proxy';

export const proxyKeys = {
  all: ['proxies'] as const,
  overview: () => [...proxyKeys.all, 'overview'] as const,
  bindings: () => [...proxyKeys.all, 'bindings'] as const,
};

export function useProxyOverview() {
  return useQuery({
    queryKey: proxyKeys.overview(),
    queryFn: ({ signal }) => getProxyOverview(undefined, signal),
  });
}

export function useProxyBindings() {
  return useQuery({
    queryKey: proxyKeys.bindings(),
    queryFn: ({ signal }) => listProxyBindings(signal),
  });
}

function useBindingMutation<T>(mutationFn: (variables: T) => Promise<unknown>) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: proxyKeys.all }),
  });
}

export function useCreateProxyBinding() {
  return useBindingMutation((body: ProxyBindingRequest) =>
    createProxyBinding(body, `proxy-binding-create-${crypto.randomUUID()}`)
  );
}

export function useUpdateProxyBinding() {
  return useBindingMutation(
    ({
      bindingProfileId,
      body,
    }: {
      bindingProfileId: string;
      body: ProxyBindingRequest;
    }) =>
      updateProxyBinding(
        bindingProfileId,
        body,
        `proxy-binding-update-${crypto.randomUUID()}`
      )
  );
}

export function useDeleteProxyBinding() {
  return useBindingMutation((bindingProfileId: string) =>
    deleteProxyBinding(
      bindingProfileId,
      `proxy-binding-delete-${crypto.randomUUID()}`
    )
  );
}
