/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type AgentBrowserNativeDialog = {
    dialogId: string;
    tabId: string;
    dialogType: 'ALERT' | 'CONFIRM' | 'PROMPT' | 'BEFOREUNLOAD';
    message: string;
    defaultPrompt: string;
    hasBrowserHandler: boolean;
};
