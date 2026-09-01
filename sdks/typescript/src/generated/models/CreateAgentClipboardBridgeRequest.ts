/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type CreateAgentClipboardBridgeRequest = {
    direction: 'USER_TO_AGENT' | 'AGENT_TO_USER';
    purpose: 'OPERATOR_COPY' | 'AUTOMATION_HANDOFF' | 'HUMAN_ASSISTANCE';
    connectionId: string;
    expectedAgentClipboardVersion: number;
    /**
     * Required only for USER_TO_AGENT and obtained from a fresh RFB ServerCutText.
     */
    value?: string;
    /**
     * Required only for USER_TO_AGENT; observations older than two minutes fail closed.
     */
    userClipboardObservedAt?: string;
};
