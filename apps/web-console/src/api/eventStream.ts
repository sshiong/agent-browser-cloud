export type EventStreamFrame = {
  id?: string;
  event: string;
  data: string;
};

export async function consumeEventStream(
  body: ReadableStream<Uint8Array>,
  onEvent: (event: EventStreamFrame) => void
) {
  const reader = body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let streamComplete = false;
  while (!streamComplete) {
    const { done, value } = await reader.read();
    streamComplete = done;
    buffer += decoder.decode(value, { stream: !done });
    buffer = buffer.replaceAll('\r\n', '\n');
    let boundary = buffer.indexOf('\n\n');
    while (boundary >= 0) {
      const frame = buffer.slice(0, boundary);
      buffer = buffer.slice(boundary + 2);
      parseEventFrame(frame, onEvent);
      boundary = buffer.indexOf('\n\n');
    }
  }
}

export function requireMatchingEventId(
  id: string | undefined,
  sequence: number,
  streamName = 'Resource stream'
) {
  if (id === undefined || !/^[0-9]+$/.test(id) || Number(id) !== sequence) {
    throw new Error(`${streamName} event ID does not match its payload`);
  }
}

function parseEventFrame(
  frame: string,
  onEvent: (event: EventStreamFrame) => void
) {
  let id: string | undefined;
  let event = 'message';
  const data: string[] = [];
  for (const line of frame.split('\n')) {
    if (!line || line.startsWith(':')) continue;
    const separator = line.indexOf(':');
    const field = separator < 0 ? line : line.slice(0, separator);
    const value =
      separator < 0 ? '' : line.slice(separator + 1).replace(/^\s/, '');
    if (field === 'id') id = value;
    if (field === 'event') event = value;
    if (field === 'data') data.push(value);
  }
  if (data.length > 0) onEvent({ id, event, data: data.join('\n') });
}
