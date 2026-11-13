import React from 'react';
import { AbsoluteFill, Easing, Img, Sequence, interpolate, staticFile, useCurrentFrame } from 'remotion';
import './film.css';

export const FPS = 30;
export const DURATION = 39 * FPS;
const sections = [
  { at: 0, end: 4, component: Intro },
  { at: 4, end: 11, component: Inventory },
  { at: 11, end: 17.5, component: Locations },
  { at: 17.5, end: 23, component: Labels },
  { at: 23, end: 28.5, component: Lookup },
  { at: 28.5, end: 36, component: Equipment },
  { at: 36, end: 39, component: End },
];
const ease = Easing.bezier(0.22, 1, 0.36, 1);
const move = (f, a, b, start = 0, end = 1) => interpolate(f, [a, b], [start, end], {
  extrapolateLeft: 'clamp', extrapolateRight: 'clamp', easing: ease,
});
const art = name => staticFile(name + '.png');
function Brand({ large = false }) {
  return <div className={'brand' + (large ? ' big' : '')}><Img src={staticFile('brand.svg')} /><span>Backroom</span></div>;
}
function Top({ section }) {
  return <div className="top"><Brand /><span>{section}</span></div>;
}
function Copy({ title, children }) {
  const f = useCurrentFrame();
  return <div className="copy" style={{ transform: 'translateY(' + move(f, 0, 23, 22, 0) + 'px)' }}>
    <h1>{title}</h1>{children && <p>{children}</p>}</div>;
}
function Frame({ name, x, y, w, h, cssWidth = 1280, left = 0, top = 0, zoom = 1, style }) {
  // Screens are 2× browser captures. Coordinates inside the frame use CSS pixels.
  return <div className="frame" style={{ left: x, top: y, width: w, height: h, ...style }}>
    <Img src={art(name)} style={{ position: 'absolute', width: cssWidth * zoom,
      left: -left * zoom, top: -top * zoom }} /></div>;
}
function Paper({ name, x, y, w, angle = 0, style }) {
  return <Img className="paper" src={art(name)} style={{ left: x, top: y, width: w,
    transform: 'rotate(' + angle + 'deg)', ...style }} />;
}
function Intro() {
  const f = useCurrentFrame();
  return <AbsoluteFill className="stage opener">
    <div className="intro-copy">
      <Brand large />
      <h1>What’s on hand?<br/>Where is it kept?</h1>
      <p>Inventory and equipment for small teams.</p>
    </div>
    <div className="intro-window" style={{ transform: 'translateY(' + move(f, 0, 86, 45, 0) + 'px) rotate(-3deg)' }}>
      <Img src={art('inventory')} />
    </div>
  </AbsoluteFill>;
}
function Inventory() {
  const f = useCurrentFrame();
  const detail = move(f, 91, 109);
  return <AbsoluteFill className="stage">
    <Top section="Inventory" /><Copy title="Check what’s on hand.">Open House espresso to see the 14 kg split across two shelves.</Copy>
    <Frame name="inventory" x={80} y={302} w={1760} h={686} zoom={move(f, 0, 100, 1.36, 1.41)} left={4} top={6} />
    <div className="row-outline" style={{ opacity: move(f, 53, 66) * (1 - detail) }} />
    <Frame name="espresso" x={80} y={302 + (1-detail)*26} w={1760} h={686} zoom={1.75} left={258} top={58 + move(f, 115, 164, 0, 63)}
      style={{ opacity: detail }} />
  </AbsoluteFill>;
}
function Locations() {
  const f = useCurrentFrame();
  const second = move(f, 79, 99);
  return <AbsoluteFill className="stage mist">
    <Top section="Locations" /><Copy title="Find the coffee shelf.">Start in the stockroom. Open a shelf to see exactly what’s stored there.</Copy>
    <Frame name="room" x={130} y={300} w={1660} h={672} zoom={1.63} left={255} top={54 + move(f, 0, 88, 0, 23)}
      style={{ opacity: 1 - second, transform: 'translateY(' + (-24*second) + 'px)' }} />
    <div className="shelf-detail" style={{ opacity: second, transform: 'translateY(' + (1-second)*32 + 'px)' }}>
      <div className="path">Stockroom <span>›</span> Coffee shelf</div>
      <Img src={art('shelf')} />
    </div>
  </AbsoluteFill>;
}
function Labels() {
  const f = useCurrentFrame();
  return <AbsoluteFill className="stage">
    <Top section="QR labels" /><Copy title="Give the shelf a label.">Choose a print layout and download a QR label that opens the shelf.</Copy>
    <Paper name="layout" x={124} y={414} w={662} />
    <div className="label-bed"><div className="paper-shadow" style={{ transform: 'translateY(' + move(f, 0, 90, 28, 0) + 'px) rotate(' + move(f, 0, 105, 3.5, -2) + 'deg)' }}>
      <Img src={art('label')} /></div></div>
  </AbsoluteFill>;
}
function Lookup() {
  const f = useCurrentFrame();
  const result = move(f, 35, 59);
  return <AbsoluteFill className="stage dark">
    <Top section="Product barcodes" /><Copy title="Look up a product barcode.">Enter the linked code from the packaging to open the item or record usage.</Copy>
    <Paper name="lookup" x={80} y={420} w={800} />
    <div className="arrow" style={{ opacity: result }}>→</div>
    <Paper name="found" x={1040} y={411 + (1-result)*22} w={800} style={{ opacity: result }} />
  </AbsoluteFill>;
}
function Equipment() {
  const f = useCurrentFrame();
  const done = move(f, 84, 106);
  return <AbsoluteFill className="stage">
    <Top section="Equipment" />
    <div style={{ opacity: 1-done }}>
      <div className="equipment-copy"><h1>Move a brew<br/>scale to the bar.</h1><p>Transfer one scale from the equipment shelf to the service shelf.</p></div>
      <Paper name="transfer" x={1072} y={157} w={722} style={{ transform: 'translateY(' + move(f, 0, 70, 20, 0) + 'px)' }} />
    </div>
    <div style={{ opacity: done }}>
      <Copy title="One scale on each shelf.">Both locations now show the updated count.</Copy>
      <Frame name="equipment" x={80} y={298} w={1760} h={696} zoom={1.76} left={257} top={114 + move(f, 102, 150, 0, 60)} />
    </div>
  </AbsoluteFill>;
}
function End() {
  const f = useCurrentFrame();
  return <AbsoluteFill className="stage close"><div style={{ transform: 'translateY(' + move(f, 0, 24, 14, 0) + 'px)' }}>
    <Brand large /><p>Everything behind the business.</p></div></AbsoluteFill>;
}
function Scene({ component: Component, first }) {
  const f = useCurrentFrame();
  return <AbsoluteFill style={{ opacity: first ? 1 : move(f, 0, 12) }}><Component /></AbsoluteFill>;
}
export function Demo() {
  return <AbsoluteFill className="film">{sections.map(({ at, end, component }) =>
    <Sequence key={at} from={at*FPS} durationInFrames={(end-at)*FPS + (end < 39 ? 12 : 0)}>
      <Scene component={component} first={at === 0} />
    </Sequence>)}</AbsoluteFill>;
}
